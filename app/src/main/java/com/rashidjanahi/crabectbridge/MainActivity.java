package com.rashidjanahi.crabectbridge;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private TextView stateView;
    private TextView ectView;
    private TextView detailsView;
    private Button toggleButton;

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            handler.postDelayed(this, 1000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(EctBridgeService.PREFS, MODE_PRIVATE);
        buildLayout();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void buildLayout() {
        int pad = dp(20);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, pad, pad, pad);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        scroll.addView(content);

        TextView title = new TextView(this);
        title.setText("CRAB ECT Bridge");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        add(content, title, 0, dp(16));

        ectView = new TextView(this);
        ectView.setTextSize(28);
        ectView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ectView.setGravity(Gravity.CENTER);
        add(content, ectView, 0, dp(16));

        stateView = new TextView(this);
        stateView.setTextSize(17);
        add(content, stateView, 0, dp(12));

        detailsView = new TextView(this);
        detailsView.setTextSize(15);
        add(content, detailsView, 0, dp(20));

        toggleButton = new Button(this);
        toggleButton.setOnClickListener(view -> toggleBridge());
        add(content, toggleButton, dp(4), dp(24));

        TextView note = new TextView(this);
        note.setTextSize(13);
        note.setText("Reads CRAB broadcasts only. It does not open Bluetooth, OBD, CAN, or MCU connections. "
                + "The status-bar destination is the OEM temperature text slot.");
        add(content, note, 0, dp(16));

        setContentView(scroll);
    }

    private void add(LinearLayout parent, TextView child, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        parent.addView(child, lp);
    }

    private void add(LinearLayout parent, Button child, int top, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        lp.bottomMargin = bottom;
        parent.addView(child, lp);
    }

    private void toggleBridge() {
        if (isBridgeServiceRunning()) {
            prefs.edit().putBoolean(EctBridgeService.PREF_ENABLED, false).apply();
            stopService(new Intent(this, EctBridgeService.class));
        } else {
            prefs.edit().putBoolean(EctBridgeService.PREF_ENABLED, true).apply();
            Intent service = new Intent(this, EctBridgeService.class);
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        }
        refreshStatus();
    }

    private void refreshStatus() {
        if (prefs == null) return;
        boolean enabled = prefs.getBoolean(EctBridgeService.PREF_ENABLED, false);
        boolean running = isBridgeServiceRunning();
        String ect = prefs.getString(EctBridgeService.PREF_DISPLAY, EctBridgeService.PLACEHOLDER);
        String state = prefs.getString(EctBridgeService.PREF_STATE, "Not started");
        int index = prefs.getInt(EctBridgeService.PREF_ECT_INDEX, -1);
        String unit = prefs.getString(EctBridgeService.PREF_UNIT, "unknown");
        String raw = prefs.getString(EctBridgeService.PREF_RAW_VALUE, "No sample received");
        long sampleTime = prefs.getLong(EctBridgeService.PREF_SAMPLE_WALL_TIME, 0L);
        String age = sampleTime == 0L ? "none" : formatAge(System.currentTimeMillis() - sampleTime);

        ectView.setText(running ? ect : EctBridgeService.PLACEHOLDER);
        String bridgeStatus = running ? "ON" : "OFF";
        if (!running && enabled) bridgeStatus += " (service stopped; auto-start armed)";
        stateView.setText("Bridge: " + bridgeStatus + "\nCRAB: " + state);
        detailsView.setText("PID 0105 item index: " + (index < 0 ? "not found" : index)
                + "\nCRAB unit: " + unit
                + "\nLast sample: " + raw + "\nAge: " + age
                + "\nStatus-bar forwarding: " + (running ? "active" : "inactive"));
        toggleButton.setText(running ? "Disable bridge" : "Start bridge");
    }

    private boolean isBridgeServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (activityManager == null) return false;

        String serviceClass = EctBridgeService.class.getName();
        for (ActivityManager.RunningServiceInfo service
                : activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (service.service != null
                    && getPackageName().equals(service.service.getPackageName())
                    && serviceClass.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    private String formatAge(long ageMs) {
        if (ageMs < 0L) ageMs = 0L;
        if (ageMs < 1000L) return "less than 1 second";
        return String.format(Locale.US, "%.1f seconds", ageMs / 1000.0);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
