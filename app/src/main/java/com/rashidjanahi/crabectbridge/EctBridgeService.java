package com.rashidjanahi.crabectbridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import java.util.Locale;

public final class EctBridgeService extends Service {
    public static final String PREFS = "crab-ect-bridge-state";
    public static final String PREF_ENABLED = "bridge-enabled";
    public static final String PREF_DISPLAY = "display-text";
    public static final String PREF_STATE = "crab-state";
    public static final String PREF_ECT_INDEX = "ect-index";
    public static final String PREF_UNIT = "ect-unit";
    public static final String PREF_RAW_VALUE = "last-raw-value";
    public static final String PREF_SAMPLE_WALL_TIME = "sample-wall-time";

    public static final String PLACEHOLDER = "🌡️ --°C";

    private static final String CRAB_PACKAGE = "altergames.carscanner";
    private static final String ACTION_CRAB_DATA = "altergames.carscanner.CRAB_DATA";
    private static final String ACTION_CRAB_GET = "altergames.carlauncher.CRAB_GET";
    private static final String ACTION_SYSTEMUI_TEMP = "com.qf.vehicle.action.TEMP_STRING";
    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String EXTRA_TEMP = "extra_string";
    private static final String CHANNEL_ID = "ect-bridge";
    private static final long CLEAR_AFTER_UNAVAILABLE_MS = 20_000L;
    private static final long STATUS_TICK_MS = 1000L;
    private static final char ITEM_SEPARATOR = '\u02c6';

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private boolean receiverRegistered;
    private boolean hasEcuState;
    private boolean ecuConnected;
    private boolean hasFreshEct;
    private int ectIndex = -1;
    private String ectUnit = "unknown";
    private String ambientTemp = "";
    private long lastSampleElapsed;
    private long unavailableSinceElapsed;
    private String lastDisplay = "";
    private int metadataRequests;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_CRAB_DATA.equals(action)) {
                handleCrabPayload(readCrabPayload(intent));
            } else if (ACTION_SYSTEMUI_TEMP.equals(action)) {
                handleAmbientTemperature(intent.getStringExtra(EXTRA_TEMP));
            }
        }
    };

    private final Runnable staleTicker = new Runnable() {
        @Override
        public void run() {
            if (hasFreshEct) {
                long now = SystemClock.elapsedRealtime();
                long unavailableSince = unavailableSinceElapsed != 0L
                        ? unavailableSinceElapsed : lastSampleElapsed;
                if (now - unavailableSince >= CLEAR_AFTER_UNAVAILABLE_MS) {
                    hasFreshEct = false;
                    unavailableSinceElapsed = 0L;
                    saveState(hasEcuState && !ecuConnected
                            ? "ECU unavailable for 20 seconds"
                            : "ECT sample stale for 20 seconds");
                    publish(PLACEHOLDER);
                }
            }
            handler.postDelayed(this, STATUS_TICK_MS);
        }
    };

    private final Runnable metadataRetry = new Runnable() {
        @Override
        public void run() {
            if (ectIndex < 0 && metadataRequests < 3) requestItems();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean(PREF_ENABLED, false)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification());
        registerInputReceiver();
        hasEcuState = false;
        ecuConnected = false;
        hasFreshEct = false;
        ectIndex = -1;
        ectUnit = "unknown";
        lastSampleElapsed = 0L;
        unavailableSinceElapsed = 0L;
        metadataRequests = 0;
        prefs.edit().putLong(PREF_SAMPLE_WALL_TIME, 0L)
                .putString(PREF_RAW_VALUE, "No sample received").apply();
        saveState("Listening for CRAB metadata");
        publish(PLACEHOLDER);
        requestItems();
        handler.removeCallbacks(metadataRetry);
        handler.postDelayed(metadataRetry, 2500L);
        handler.postDelayed(metadataRetry, 5000L);
        handler.removeCallbacks(staleTicker);
        handler.postDelayed(staleTicker, STATUS_TICK_MS);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(metadataRetry);
        handler.removeCallbacks(staleTicker);
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver);
            } catch (IllegalArgumentException ignored) {
            }
            receiverRegistered = false;
        }
        if (!prefs.getBoolean(PREF_ENABLED, false)) {
            sendSystemUiText(ambientTemp, true);
            prefs.edit().putString(PREF_DISPLAY, ambientTemp).apply();
            saveState("Bridge stopped");
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerInputReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_CRAB_DATA);
        filter.addAction(ACTION_SYSTEMUI_TEMP);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(receiver, filter);
        }
        receiverRegistered = true;
    }

    private void requestItems() {
        metadataRequests++;
        Intent request = new Intent(ACTION_CRAB_GET);
        request.setPackage(CRAB_PACKAGE);
        request.putExtra("get", "get_items");
        sendBroadcast(request);
    }

    private String readCrabPayload(Intent intent) {
        String payload = intent.getStringExtra("data");
        if (payload == null) payload = intent.getStringExtra("free");
        if (payload != null) return payload;

        // CRAB versions use different extra keys for free and licensed output.
        if (intent.getExtras() != null) {
            for (String key : intent.getExtras().keySet()) {
                Object value = intent.getExtras().get(key);
                if (value instanceof String) return (String) value;
            }
        }
        return null;
    }

    private void handleCrabPayload(String payload) {
        if (TextUtils.isEmpty(payload)) return;
        if (payload.startsWith("STAT2")) {
            handleCrabState(payload.substring(5));
        } else if (payload.startsWith("ITEM")) {
            handleItems(payload.substring(4));
        } else if (payload.startsWith("DATA")) {
            handleData(payload.substring(4));
        } else if (payload.startsWith("ERROR")) {
            hasEcuState = true;
            ecuConnected = false;
            markEctUnavailable("CRAB reported an error");
        }
    }

    private void handleCrabState(String state) {
        if (state == null || state.length() < 2) return;
        hasEcuState = true;
        ecuConnected = state.charAt(1) == '2';
        if (!ecuConnected) {
            markEctUnavailable("ECU disconnected");
        } else {
            unavailableSinceElapsed = 0L;
            if (!hasFreshEct) {
                saveState("ECU connected; waiting for PID 0105");
            }
        }
    }

    private void markEctUnavailable(String reason) {
        if (hasFreshEct) {
            if (unavailableSinceElapsed == 0L) {
                unavailableSinceElapsed = SystemClock.elapsedRealtime();
            }
            saveState(reason + "; holding last reading for up to 20 seconds");
        } else {
            saveState(reason);
            publish(PLACEHOLDER);
        }
    }

    private void handleItems(String encodedItems) {
        ectIndex = -1;
        ectUnit = "unknown";
        if ("NO".equals(encodedItems)) {
            saveState("CRAB says item data is unavailable");
            saveMetadata();
            return;
        }

        String[] items = encodedItems.split(String.valueOf(ITEM_SEPARATOR), -1);
        for (int index = 0; index < items.length; index++) {
            String pid = "";
            String unit = "";
            String[] fields = items[index].split(",", -1);
            for (String field : fields) {
                int colon = field.indexOf(':');
                if (colon <= 0) continue;
                String key = field.substring(0, colon).trim();
                String value = field.substring(colon + 1).trim();
                if ("PID".equals(key)) pid = value;
                if ("unit".equals(key)) unit = value;
            }
            if ("0105".equalsIgnoreCase(pid)) {
                ectIndex = index;
                ectUnit = TextUtils.isEmpty(unit) ? "°C (OBD PID default)" : unit;
                break;
            }
        }

        saveMetadata();
        if (ectIndex < 0) {
            saveState("CRAB item list has no PID 0105");
        } else if (ecuConnected) {
            saveState("PID 0105 found; waiting for live data");
        } else {
            saveState(hasEcuState ? "ECU disconnected" : "PID 0105 found; waiting for CRAB connection");
        }
    }

    private void handleData(String encodedData) {
        if (ectIndex < 0) return;
        String[] fields = encodedData.split(";", -1);
        if (fields.length < 3) return;

        final int index;
        try {
            index = Integer.parseInt(fields[0].trim());
        } catch (NumberFormatException ignored) {
            return;
        }
        if (index != ectIndex || !"0".equals(fields[2].trim())) return;
        if (hasEcuState && !ecuConnected) return;

        final float value;
        try {
            value = Float.parseFloat(fields[1].trim());
        } catch (NumberFormatException ignored) {
            return;
        }

        float celsius = toCelsius(value, ectUnit);
        if (Float.isNaN(celsius) || celsius < -40.0f || celsius > 215.0f) {
            saveState("PID 0105 value or unit is invalid");
            return;
        }

        int rounded = Math.round(celsius);
        hasFreshEct = true;
        ecuConnected = true;
        lastSampleElapsed = SystemClock.elapsedRealtime();
        unavailableSinceElapsed = 0L;
        prefs.edit().putString(PREF_RAW_VALUE, fields[1].trim() + " " + ectUnit)
                .putLong(PREF_SAMPLE_WALL_TIME, System.currentTimeMillis()).apply();
        saveState("Live PID 0105 received");
        publish(String.format(Locale.US, "🌡️ %d°C", rounded));
    }

    private float toCelsius(float value, String unit) {
        String normalized = unit == null ? "" : unit.trim().toLowerCase(Locale.US);
        if (normalized.contains("°f") || normalized.equals("f") || normalized.contains("fahrenheit")) {
            return (value - 32.0f) * (5.0f / 9.0f);
        }
        if (normalized.isEmpty() || normalized.contains("°c") || normalized.equals("c")
                || normalized.contains("celsius") || normalized.contains("(obd pid default)")) {
            return value;
        }
        return Float.NaN;
    }

    private void handleAmbientTemperature(String value) {
        if (value == null) return;
        ambientTemp = value;
        if (shouldHoldLastEct()) {
            // The OEM may also publish this slot. Reassert the most recent ECT after its update.
            sendSystemUiText(prefs.getString(PREF_DISPLAY, PLACEHOLDER), true);
        }
    }

    private boolean shouldHoldLastEct() {
        if (!hasFreshEct) return false;
        long unavailableSince = unavailableSinceElapsed != 0L
                ? unavailableSinceElapsed : lastSampleElapsed;
        return SystemClock.elapsedRealtime() - unavailableSince < CLEAR_AFTER_UNAVAILABLE_MS;
    }

    private void publish(String text) {
        if (!prefs.getBoolean(PREF_ENABLED, false)) return;
        String value = text == null ? "" : text;
        if (value.equals(lastDisplay)) return;
        lastDisplay = value;
        prefs.edit().putString(PREF_DISPLAY, value).apply();
        sendSystemUiText(value, false);
    }

    private void sendSystemUiText(String text, boolean force) {
        if (!force && !prefs.getBoolean(PREF_ENABLED, false)) return;
        String value = text == null ? "" : text;
        lastDisplay = value;
        Intent update = new Intent(ACTION_SYSTEMUI_TEMP);
        update.setPackage(SYSTEMUI_PACKAGE);
        update.putExtra(EXTRA_TEMP, value);
        sendBroadcast(update);
    }

    private void saveMetadata() {
        prefs.edit().putInt(PREF_ECT_INDEX, ectIndex).putString(PREF_UNIT, ectUnit).apply();
    }

    private void saveState(String state) {
        prefs.edit().putString(PREF_STATE, state).apply();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "CRAB ECT bridge", NotificationManager.IMPORTANCE_MIN);
        channel.setDescription("Keeps the passive CRAB temperature bridge alive");
        channel.setShowBadge(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle("CRAB ECT bridge")
                .setContentText("Listening passively for PID 0105")
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }
}
