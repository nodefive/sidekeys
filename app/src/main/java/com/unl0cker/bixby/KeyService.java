package com.unl0cker.bixby;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Hardware key handling.
 *
 * Bixby key: each press toggles the rear flashlight. One UI keeps this key for itself
 * (interceptKeyTq result without PASS_TO_USER), so Accessibility never receives it. Instead
 * the service follows two system_server log tags (needs READ_LOGS, granted over adb):
 *   InputReader: Input event(N): value=1|0      hardware key down/up (any gpio key)
 *   PhoneWindowManagerExt: getIntentBixbyService  logged for the Bixby key only
 * A getIntentBixbyService line right after a key-down is one Bixby press.
 *
 * Volume keys while music plays (not in a call), per key (up = forward, down = back):
 *   tap                  next / previous track
 *   hold                 media volume, one step every VOLUME_REPEAT_MS after the hold delay
 *   tap, then hold       scrub forward / back at the seek speed (seek option on): small seekTo
 *                        steps every SEEK_TICK_MS, so the position slides instead of jumping;
 *                        with speed-up on, the speed doubles every SEEK_RAMP_MS held (max 8x)
 *   tap, tap             two skips (seek option on)
 * With seek on, a tap waits SECOND_PRESS_WINDOW_MS for a second press before skipping.
 * Only while one of the chosen apps is the one playing (its media session is in
 * STATE_PLAYING). Finding that session needs notification access (MediaListener); without
 * it, or when another app plays (videos, voice notes, audiobooks), keys pass through
 * untouched.
 *
 * Both features follow the switches on the main screen (Prefs). The Accessibility service
 * also keeps the process alive for the log watcher.
 */
public class KeyService extends AccessibilityService {

    private static final long VOLUME_REPEAT_MS = 150;
    private static final long SEEK_TICK_MS = 150;
    /** Speed up while holding: the seek speed doubles every SEEK_RAMP_MS, at most 2^3 = 8x. */
    private static final long SEEK_RAMP_MS = 2000;
    private static final int SEEK_RAMP_MAX_DOUBLINGS = 3;
    private static final long SECOND_PRESS_WINDOW_MS = 300;
    private static final long LOG_RESTART_MS = 2000;

    /** Volume key gesture state. */
    private enum Gesture { IDLE, FIRST_DOWN, WAIT_SECOND, SECOND_DOWN, VOLUME, SEEK }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private boolean volumeEnabled;
    private long holdMs;
    private boolean seekEnabled;
    private int seekSpeed;
    private boolean seekRamp;
    private Set<String> apps = new HashSet<>();
    private LogWatcher watcher;

    private AudioManager audio;
    private MediaSessionManager sessions;
    private CameraManager camera;
    private String torchId;
    private boolean torchOn;

    private Gesture gesture = Gesture.IDLE;
    /** Volume key of the current gesture. */
    private int gestureKey;
    /**
     * Player being scrubbed, picked once when the seek starts: right after a seekTo the session
     * reports buffering, so looking it up again on every tick would lose it.
     */
    private MediaController seekController;
    /** Position the scrub has reached; each tick moves it by the current speed * SEEK_TICK_MS. */
    private long seekPosition;
    /** When the current scrub started (elapsedRealtime), for speeding up while holding. */
    private long seekStartedAt;

    /** Kept as a field: SharedPreferences holds listeners weakly. */
    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (p, key) -> applySettings();

    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchId)) {
                torchOn = enabled;
            }
        }
    };

    private final Runnable volumeRepeat = new Runnable() {
        @Override
        public void run() {
            adjustVolume();
            handler.postDelayed(this, VOLUME_REPEAT_MS);
        }
    };

    private final Runnable seekRepeat = new Runnable() {
        @Override
        public void run() {
            long step = currentSeekSpeed() * SEEK_TICK_MS;
            scrubTo(seekPosition + (gestureKey == KeyEvent.KEYCODE_VOLUME_UP ? step : -step));
            handler.postDelayed(this, SEEK_TICK_MS);
        }
    };

    /** Key held past the hold delay: first press changes volume, second press seeks. */
    private final Runnable holdStart = () -> {
        if (gesture == Gesture.FIRST_DOWN) {
            gesture = Gesture.VOLUME;
            volumeRepeat.run();
        } else if (gesture == Gesture.SECOND_DOWN) {
            gesture = Gesture.SEEK;
            startSeek();
        }
    };

    /** No second press came after a tap: it was a plain skip. */
    private final Runnable pendingSkip = () -> {
        if (gesture == Gesture.WAIT_SECOND) {
            gesture = Gesture.IDLE;
            skip(gestureKey);
        }
    };

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
        sessions = getSystemService(MediaSessionManager.class);
        camera = getSystemService(CameraManager.class);
        torchId = findTorchCamera();
        if (torchId != null) {
            camera.registerTorchCallback(torchCallback, handler);
        }
        prefs = Prefs.get(this);
        prefs.registerOnSharedPreferenceChangeListener(prefsListener);
        applySettings();
    }

    private void applySettings() {
        volumeEnabled = Prefs.volumeEnabled(prefs);
        holdMs = Prefs.holdMs(prefs);
        seekEnabled = Prefs.seekEnabled(prefs);
        seekSpeed = Prefs.seekSpeed(prefs);
        seekRamp = Prefs.seekRamp(prefs);
        apps = Prefs.apps(prefs);
        boolean bixby = Prefs.bixbyEnabled(prefs) && torchId != null;
        if (bixby && watcher == null) {
            watcher = new LogWatcher();
            Thread t = new Thread(watcher, "bixby-log");
            t.setDaemon(true);
            t.start();
        } else if (!bixby && watcher != null) {
            watcher.stop();
            watcher = null;
        }
    }

    // ---- volume keys -------------------------------------------------------------------

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
            return onVolumeKey(event);
        }
        return false;
    }

    private boolean onVolumeKey(KeyEvent event) {
        int code = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() > 0) {
                return keyHeld() && gestureKey == code;
            }
            if (gesture == Gesture.WAIT_SECOND) {
                handler.removeCallbacks(pendingSkip);
                if (code == gestureKey) {
                    gesture = Gesture.SECOND_DOWN;
                    handler.postDelayed(holdStart, holdMs);
                    return true;
                }
                // The other volume key: finish the first tap, then treat this press as new.
                gesture = Gesture.IDLE;
                skip(gestureKey);
            } else if (gesture != Gesture.IDLE) {
                return gestureKey == code;
            }
            if (!volumeEnabled || !shouldSkipTracks()) {
                return false;
            }
            gesture = Gesture.FIRST_DOWN;
            gestureKey = code;
            handler.postDelayed(holdStart, holdMs);
            return true;
        }
        // Finish a press that started while the feature was on, even if it was just turned off.
        if (event.getAction() == KeyEvent.ACTION_UP && keyHeld() && code == gestureKey) {
            handler.removeCallbacks(holdStart);
            handler.removeCallbacks(volumeRepeat);
            handler.removeCallbacks(seekRepeat);
            seekController = null;
            switch (gesture) {
                case FIRST_DOWN:
                    if (seekEnabled) {
                        gesture = Gesture.WAIT_SECOND;
                        handler.postDelayed(pendingSkip, SECOND_PRESS_WINDOW_MS);
                    } else {
                        gesture = Gesture.IDLE;
                        skip(code);
                    }
                    break;
                case SECOND_DOWN:
                    gesture = Gesture.IDLE;
                    skip(code);
                    skip(code);
                    break;
                default:
                    gesture = Gesture.IDLE;
                    break;
            }
            return true;
        }
        return false;
    }

    private boolean keyHeld() {
        return gesture == Gesture.FIRST_DOWN || gesture == Gesture.SECOND_DOWN
                || gesture == Gesture.VOLUME || gesture == Gesture.SEEK;
    }

    private boolean shouldSkipTracks() {
        if (!audio.isMusicActive() || audio.getMode() != AudioManager.MODE_NORMAL) {
            return false;
        }
        return allowedPlayer() != null;
    }

    /**
     * The media session that is playing, if it belongs to one of the chosen apps. Null when
     * another app is playing, nothing is, or notification access is missing.
     */
    private MediaController allowedPlayer() {
        List<MediaController> controllers;
        try {
            controllers = sessions.getActiveSessions(new ComponentName(this, MediaListener.class));
        } catch (SecurityException e) {
            return null;
        }
        for (MediaController c : controllers) {
            PlaybackState s = c.getPlaybackState();
            if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                return apps.contains(c.getPackageName()) ? c : null;
            }
        }
        return null;
    }

    private void adjustVolume() {
        int direction = gestureKey == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
    }

    /** Skips in the allowed player; media keys if its session is between states. */
    private void skip(int volumeKey) {
        boolean next = volumeKey == KeyEvent.KEYCODE_VOLUME_UP;
        MediaController controller = allowedPlayer();
        if (controller != null) {
            if (next) {
                controller.getTransportControls().skipToNext();
            } else {
                controller.getTransportControls().skipToPrevious();
            }
            return;
        }
        sendMediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    /**
     * Starts scrubbing the allowed player from its current position. If it cannot seek, sends
     * one fast-forward/rewind media key instead (many players ignore it) and does not tick.
     */
    private void startSeek() {
        boolean forward = gestureKey == KeyEvent.KEYCODE_VOLUME_UP;
        MediaController controller = allowedPlayer();
        PlaybackState state = controller != null ? controller.getPlaybackState() : null;
        if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0) {
            sendMediaKey(forward ? KeyEvent.KEYCODE_MEDIA_FAST_FORWARD : KeyEvent.KEYCODE_MEDIA_REWIND);
            return;
        }
        long position = state.getPosition();
        if (state.getState() == PlaybackState.STATE_PLAYING) {
            long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
            position += (long) (elapsed * state.getPlaybackSpeed());
        }
        seekController = controller;
        seekPosition = position;
        seekStartedAt = SystemClock.elapsedRealtime();
        seekRepeat.run();
    }

    /** Seek speed, doubled for every SEEK_RAMP_MS held when speeding up is on. */
    private long currentSeekSpeed() {
        if (!seekRamp) {
            return seekSpeed;
        }
        long held = SystemClock.elapsedRealtime() - seekStartedAt;
        int doublings = (int) Math.min(held / SEEK_RAMP_MS, SEEK_RAMP_MAX_DOUBLINGS);
        return (long) seekSpeed << doublings;
    }

    /**
     * One scrub tick. The position is tracked here rather than read back from the player, so it
     * moves at exactly seekSpeed whatever the player does between ticks.
     */
    private void scrubTo(long target) {
        MediaController controller = seekController;
        if (controller == null) {
            return;
        }
        MediaMetadata metadata = controller.getMetadata();
        long duration = metadata != null ? metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) : 0;
        target = Math.max(0, target);
        if (duration > 0) {
            target = Math.min(target, Math.max(0, duration - 1000));
        }
        seekPosition = target;
        controller.getTransportControls().seekTo(target);
    }

    private void sendMediaKey(int keyCode) {
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
    }

    // ---- Bixby key / flashlight ----------------------------------------------------------

    /** Follows the system log for Bixby presses; restarts logcat if it exits. */
    private final class LogWatcher implements Runnable {
        private volatile boolean active = true;
        private volatile Process process;

        void stop() {
            active = false;
            Process p = process;
            if (p != null) {
                p.destroy();
            }
        }

        @Override
        public void run() {
            long since = System.currentTimeMillis();
            while (active) {
                String start = String.format(Locale.ROOT, "%d.%03d", since / 1000, since % 1000);
                try {
                    process = new ProcessBuilder("logcat", "-b", "main,system", "-v", "brief",
                            "-T", start, "InputReader:D", "PhoneWindowManagerExt:D", "*:S")
                            .redirectErrorStream(true)
                            .start();
                    if (!active) {
                        process.destroy();
                        break;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(process.getInputStream()))) {
                        boolean keyDown = false;
                        String line;
                        while (active && (line = reader.readLine()) != null) {
                            if (line.contains("Input event(")) {
                                keyDown = line.contains("value=1");
                            } else if (keyDown && line.contains("getIntentBixbyService")) {
                                keyDown = false;
                                handler.post(KeyService.this::toggleTorch);
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // logcat could not start or its pipe closed: retry below.
                } finally {
                    Process p = process;
                    if (p != null) {
                        p.destroy();
                    }
                }
                since = System.currentTimeMillis();
                if (active) {
                    SystemClock.sleep(LOG_RESTART_MS);
                }
            }
        }
    }

    private void toggleTorch() {
        if (torchId == null || watcher == null) {
            return;
        }
        try {
            camera.setTorchMode(torchId, !torchOn);
        } catch (CameraAccessException | IllegalArgumentException ignored) {
            // Camera in use by another app: nothing to do.
        }
    }

    private String findTorchCamera() {
        try {
            for (String id : camera.getCameraIdList()) {
                CameraCharacteristics c = camera.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (Boolean.TRUE.equals(flash) && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
            }
        } catch (CameraAccessException ignored) {
            // No camera service: torch stays unavailable.
        }
        return null;
    }

    // ---- lifecycle ---------------------------------------------------------------------

    /** Tracks the package of the app in front; ignores overlays, keyboards and this app. */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // No event types requested.
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        if (prefs != null) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
        }
        if (watcher != null) {
            watcher.stop();
            watcher = null;
        }
        handler.removeCallbacksAndMessages(null);
        if (torchId != null) {
            camera.unregisterTorchCallback(torchCallback);
        }
        super.onDestroy();
    }
}
