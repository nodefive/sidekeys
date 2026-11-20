package com.unl0cker.bixby;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
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
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/** Service and permission status, feature switches and track skip options. */
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
        bindSwitch(R.id.row_seek, R.id.switch_seek, Prefs.KEY_SEEK, Prefs.seekEnabled(prefs));
        bindSwitch(R.id.row_seek_ramp, R.id.switch_seek_ramp, Prefs.KEY_SEEK_RAMP, Prefs.seekRamp(prefs));

        bindSlider(R.id.hold_slider, R.id.hold_value, R.string.hold_value_fmt, Prefs.KEY_HOLD_MS,
                Prefs.HOLD_MIN_MS, Prefs.HOLD_MAX_MS, Prefs.HOLD_STEP_MS, Prefs.holdMs(prefs));
        bindSlider(R.id.seek_step_slider, R.id.seek_step_value, R.string.seek_step_fmt,
                Prefs.KEY_SEEK_SPEED, Prefs.SEEK_SPEED_MIN, Prefs.SEEK_SPEED_MAX,
                Prefs.SEEK_SPEED_STEP, Prefs.seekSpeed(prefs));

        findViewById(R.id.row_apps).setOnClickListener(v ->
                startActivity(new Intent(this, AppsActivity.class)));
        findViewById(R.id.open_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.open_notif_settings).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

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

    /** Slider over min..max in steps; shows the value live, saves when released. */
    private void bindSlider(int sliderId, int valueId, int formatId, String key,
                            int min, int max, int step, int current) {
        SeekBar slider = findViewById(sliderId);
        TextView value = findViewById(valueId);
        slider.setMax((max - min) / step);
        slider.setProgress((Math.max(min, Math.min(max, current)) - min) / step);
        value.setText(getString(formatId, current));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                value.setText(getString(formatId, min + progress * step));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                prefs.edit().putInt(key, min + bar.getProgress() * step).apply();
            }
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
        show(R.id.logs_card, Prefs.bixbyEnabled(prefs) && !logsGranted);
        tint(R.id.logs_dot, R.color.warn);

        boolean volume = Prefs.volumeEnabled(prefs);
        boolean seek = Prefs.seekEnabled(prefs);
        show(R.id.notif_card, volume && !notificationAccess());
        tint(R.id.notif_dot, R.color.warn);

        // Track skip options only matter while track skip is on; seek step only with seek.
        for (int id : new int[] {R.id.row_apps, R.id.row_hold, R.id.row_seek}) {
            setRowEnabled(id, volume);
        }
        setRowEnabled(R.id.row_seek_step, volume && seek);
        setRowEnabled(R.id.row_seek_ramp, volume && seek);
        findViewById(R.id.hold_slider).setEnabled(volume);
        findViewById(R.id.seek_step_slider).setEnabled(volume && seek);

        // Count only installed apps; the stored list may name apps this phone does not have.
        PackageManager pm = getPackageManager();
        int count = 0;
        for (String pkg : Prefs.apps(prefs)) {
            if (pm.getLaunchIntentForPackage(pkg) != null) {
                count++;
            }
        }
        ((TextView) findViewById(R.id.apps_summary)).setText(
                getResources().getQuantityString(R.plurals.apps_summary, count, count));
    }

    private void setRowEnabled(int id, boolean enabled) {
        View row = findViewById(id);
        row.setEnabled(enabled);
        row.setAlpha(enabled ? 1f : 0.4f);
    }

    private void show(int id, boolean visible) {
        findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void tint(int viewId, int colorId) {
        ((ImageView) findViewById(viewId)).setImageTintList(
                ColorStateList.valueOf(getColor(colorId)));
    }

    private boolean notificationAccess() {
        return getSystemService(NotificationManager.class).isNotificationListenerAccessGranted(
                new ComponentName(this, MediaListener.class));
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
