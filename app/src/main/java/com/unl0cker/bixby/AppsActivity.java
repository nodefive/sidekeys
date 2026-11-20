package com.unl0cker.bixby;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Launchable apps with a checkbox each; track skip works while a checked app is playing. */
public class AppsActivity extends Activity {

    private static final class App {
        final String pkg;
        final String label;
        final Drawable icon;

        App(String pkg, String label, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.icon = icon;
        }
    }

    private SharedPreferences prefs;
    private Set<String> chosen;
    private final List<App> apps = new ArrayList<>();
    private Adapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apps);
        prefs = Prefs.get(this);
        chosen = Prefs.apps(prefs);

        findViewById(R.id.back).setOnClickListener(v -> finish());

        ListView list = findViewById(R.id.list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            String pkg = apps.get(position).pkg;
            if (!chosen.remove(pkg)) {
                chosen.add(pkg);
            }
            Prefs.setApps(prefs, chosen);
            ((CheckBox) view.findViewById(R.id.check)).setChecked(chosen.contains(pkg));
        });

        new Thread(this::loadApps, "load-apps").start();
    }

    /** Checked apps first, then alphabetical. Runs off the main thread (icons are slow). */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Set<String> seen = new HashSet<>();
        List<App> loaded = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            loaded.add(new App(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm)));
        }
        Collator collator = Collator.getInstance();
        Set<String> initial = new HashSet<>(chosen);
        loaded.sort(Comparator
                .comparing((App a) -> !initial.contains(a.pkg))
                .thenComparing(a -> a.label, collator));
        runOnUiThread(() -> {
            if (isDestroyed()) {
                return;
            }
            apps.clear();
            apps.addAll(loaded);
            adapter.notifyDataSetChanged();
            findViewById(R.id.loading).setVisibility(View.GONE);
        });
    }

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView
                    : LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app, parent, false);
            App app = apps.get(position);
            ((ImageView) row.findViewById(R.id.icon)).setImageDrawable(app.icon);
            ((TextView) row.findViewById(R.id.label)).setText(app.label);
            ((TextView) row.findViewById(R.id.pkg)).setText(app.pkg);
            ((CheckBox) row.findViewById(R.id.check)).setChecked(chosen.contains(app.pkg));
            return row;
        }
    }
}
