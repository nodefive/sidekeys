package com.unl0cker.bixby;

import android.content.Context;
import android.content.SharedPreferences;

/** Feature switches shared by the main screen and the key service (same process). */
final class Prefs {

    static final String KEY_BIXBY = "bixby_torch";
    static final String KEY_VOLUME = "volume_skip";

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
}
