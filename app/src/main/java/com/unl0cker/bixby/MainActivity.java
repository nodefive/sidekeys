package com.unl0cker.bixby;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;

/** Service status, log access status and the two feature switches. */
public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private AccessibilityManager accessibility;
    private final AccessibilityManager.AccessibilityStateChangeListener stateListener =
            enabled -> refresh();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
        accessibility = getSystemService(AccessibilityManager.class);

        bindSwitch(R.id.row_bixby, R.id.switch_bixby, Prefs.KEY_BIXBY, Prefs.bixbyEnabled(prefs));
        bindSwitch(R.id.row_volume, R.id.switch_volume, Prefs.KEY_VOLUME, Prefs.volumeEnabled(prefs));

        findViewById(R.id.open_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        TextView version = findViewById(R.id.version);
        try {
            String name = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            version.setText(getString(R.string.version_fmt, name));
        } catch (PackageManager.NameNotFoundException e) {
            version.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        accessibility.addAccessibilityStateChangeListener(stateListener);
        refresh();
    }

    @Override
    protected void onPause() {
        accessibility.removeAccessibilityStateChangeListener(stateListener);
        super.onPause();
    }

    private void bindSwitch(int rowId, int switchId, String key, boolean checked) {
        Switch toggle = findViewById(switchId);
        toggle.setChecked(checked);
        findViewById(rowId).setOnClickListener(v -> {
            toggle.toggle();
            prefs.edit().putBoolean(key, toggle.isChecked()).apply();
            refresh();
        });
    }

    private void refresh() {
        boolean active = isServiceEnabled();
        tint(R.id.status_dot, active ? R.color.ok : R.color.warn);
        ((TextView) findViewById(R.id.status_title)).setText(
                active ? R.string.status_on_title : R.string.status_off_title);
        ((TextView) findViewById(R.id.status_text)).setText(
                active ? R.string.status_on_text : R.string.status_off_text);
        findViewById(R.id.open_settings).setVisibility(active ? View.GONE : View.VISIBLE);

        boolean logsGranted = checkSelfPermission(Manifest.permission.READ_LOGS)
                == PackageManager.PERMISSION_GRANTED;
        boolean needLogs = Prefs.bixbyEnabled(prefs) && !logsGranted;
        findViewById(R.id.logs_card).setVisibility(needLogs ? View.VISIBLE : View.GONE);
        tint(R.id.logs_dot, R.color.warn);
    }

    private void tint(int viewId, int colorId) {
        ((ImageView) findViewById(viewId)).setImageTintList(
                ColorStateList.valueOf(getColor(colorId)));
    }

    private boolean isServiceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) {
            return false;
        }
        ComponentName self = new ComponentName(this, KeyService.class);
        for (String entry : enabled.split(":")) {
            if (self.equals(ComponentName.unflattenFromString(entry))) {
                return true;
            }
        }
        return false;
    }
}
