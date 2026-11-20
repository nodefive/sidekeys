package com.unl0cker.bixby;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Settings shared by the main screen and the key service (same process). */
final class Prefs {

    static final String KEY_BIXBY = "bixby_torch";
    static final String KEY_VOLUME = "volume_skip";
    static final String KEY_APPS = "skip_apps";
    static final String KEY_HOLD_MS = "hold_ms";
    static final String KEY_SEEK = "seek";
    static final String KEY_SEEK_SPEED = "seek_speed";
    static final String KEY_SEEK_RAMP = "seek_ramp";

    /** Hold delay slider: quick presses measured on the S10e lasted 260-384 ms. */
    static final int HOLD_MIN_MS = 250;
    static final int HOLD_MAX_MS = 1000;
    static final int HOLD_STEP_MS = 50;
    static final int HOLD_DEFAULT_MS = 500;

    /** Seek speed slider: seconds of track per second of holding. */
    static final int SEEK_SPEED_MIN = 2;
    static final int SEEK_SPEED_MAX = 30;
    static final int SEEK_SPEED_STEP = 2;
    static final int SEEK_SPEED_DEFAULT = 8;

    /**
     * Track skip works while one of these apps is the one playing. Defaults are common music
     * players; only used until the user edits the list.
     */
    static final Set<String> DEFAULT_APPS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "com.spotify.music",
            "com.google.android.apps.youtube.music",
            "com.sec.android.app.music",
            "com.apple.android.music",
            "deezer.android.app",
            "com.amazon.mp3",
            "com.aspiro.tidal",
            "com.soundcloud.android",
            "com.pandora.android",
            "com.maxmpz.audioplayer",
            "in.krosbits.musicolet",
            "code.name.monkey.retromusic",
            "com.kapp.youtube.final",
            "org.akanework.gramophone")));

    private Prefs() {
    }

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean bixbyEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_BIXBY, true);
    }

    static boolean volumeEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_VOLUME, true);
    }

    static int holdMs(SharedPreferences prefs) {
        return prefs.getInt(KEY_HOLD_MS, HOLD_DEFAULT_MS);
    }

    static boolean seekEnabled(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_SEEK, false);
    }

    static int seekSpeed(SharedPreferences prefs) {
        return prefs.getInt(KEY_SEEK_SPEED, SEEK_SPEED_DEFAULT);
    }

    static boolean seekRamp(SharedPreferences prefs) {
        return prefs.getBoolean(KEY_SEEK_RAMP, true);
    }

    /** Returns a copy; the stored set must not be modified in place. */
    static Set<String> apps(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(KEY_APPS, DEFAULT_APPS));
    }

    static void setApps(SharedPreferences prefs, Set<String> packages) {
        prefs.edit().putStringSet(KEY_APPS, new HashSet<>(packages)).apply();
    }
}
