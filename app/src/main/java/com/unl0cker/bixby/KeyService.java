package com.unl0cker.bixby;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

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
 * Volume keys while music plays (not in a call): a quick press sends next/previous track,
 * holding past LONG_PRESS_MS changes the media volume one step every REPEAT_MS.
 * Everything else, and volume keys when nothing plays, passes through untouched.
 *
 * Both features follow the switches on the main screen (Prefs). The Accessibility service
 * also keeps the process alive for the log watcher.
 */
public class KeyService extends AccessibilityService {

    /** Quick presses measured on the S10e lasted 260-384 ms. */
    private static final long LONG_PRESS_MS = 500;
    private static final long REPEAT_MS = 150;
    private static final long LOG_RESTART_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SharedPreferences prefs;
    private boolean volumeEnabled;
    private LogWatcher watcher;

    private AudioManager audio;
    private CameraManager camera;
    private String torchId;
    private boolean torchOn;

    /** Volume key currently owned by this service, or 0. */
    private int heldKey;
    private boolean longPressActive;

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
            handler.postDelayed(this, REPEAT_MS);
        }
    };

    private final Runnable longPressStart = () -> {
        longPressActive = true;
        volumeRepeat.run();
    };

    @Override
    protected void onServiceConnected() {
        audio = getSystemService(AudioManager.class);
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
            if (event.getRepeatCount() > 0 || heldKey != 0) {
                return heldKey == code;
            }
            if (!volumeEnabled || !mediaPlaying()) {
                return false;
            }
            heldKey = code;
            longPressActive = false;
            handler.postDelayed(longPressStart, LONG_PRESS_MS);
            return true;
        }
        // Finish a press that started while the feature was on, even if it was just turned off.
        if (event.getAction() == KeyEvent.ACTION_UP && heldKey == code) {
            handler.removeCallbacks(longPressStart);
            handler.removeCallbacks(volumeRepeat);
            if (!longPressActive) {
                sendMediaKey(code == KeyEvent.KEYCODE_VOLUME_UP
                        ? KeyEvent.KEYCODE_MEDIA_NEXT
                        : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
            heldKey = 0;
            longPressActive = false;
            return true;
        }
        return false;
    }

    private boolean mediaPlaying() {
        return audio.isMusicActive() && audio.getMode() == AudioManager.MODE_NORMAL;
    }

    private void adjustVolume() {
        int direction = heldKey == KeyEvent.KEYCODE_VOLUME_UP
                ? AudioManager.ADJUST_RAISE
                : AudioManager.ADJUST_LOWER;
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI);
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
