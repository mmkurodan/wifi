package com.example.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;

/**
 * Foreground service that manages the WiFi Hotspot.
 */
public class HotspotService extends Service {
    private static final String TAG = "HotspotService";
    private static final String CHANNEL_ID = "hotspot_channel";
    private static final int NOTIFICATION_ID = 2;
    public static final String EXTRA_SSID = "extra_ssid";
    public static final String EXTRA_PASSWORD = "extra_password";

    private WifiHotspotManager hotspotManager;
    private final IBinder binder = new LocalBinder();
    private HotspotCallback callback;
    private String desiredSsid;
    private String desiredPassword;

    public interface HotspotCallback {
        void onHotspotStarted(String ssid, String password);
        void onHotspotStopped();
        void onHotspotFailed(int reason);
    }

    public class LocalBinder extends Binder {
        HotspotService getService() {
            return HotspotService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        hotspotManager = new WifiHotspotManager(this);
        
        hotspotManager.setCallback(new WifiHotspotManager.HotspotCallback() {
            @Override
            public void onStarted(String ssid, String password) {
                Log.i(TAG, "Hotspot started: " + ssid);
                AppLogBuffer.add(TAG, "Hotspot started: " + ssid);
                AppPreferences.saveHotspotIfEmpty(HotspotService.this, ssid, password);
                if (callback != null) {
                    callback.onHotspotStarted(ssid, password);
                }
            }

            @Override
            public void onStopped() {
                Log.i(TAG, "Hotspot stopped");
                AppLogBuffer.add(TAG, "Hotspot stopped");
                if (callback != null) {
                    callback.onHotspotStopped();
                }
            }

            @Override
            public void onFailed(int reason) {
                Log.e(TAG, "Hotspot failed: " + reason);
                AppLogBuffer.add(TAG, "Hotspot failed: " + reason);
                if (callback != null) {
                    callback.onHotspotFailed(reason);
                }
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopHotspot();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            desiredSsid = intent.getStringExtra(EXTRA_SSID);
            desiredPassword = intent.getStringExtra(EXTRA_PASSWORD);
        }
        if (TextUtils.isEmpty(desiredSsid)) {
            desiredSsid = AppPreferences.getSsid(this);
        }
        if (TextUtils.isEmpty(desiredPassword)) {
            desiredPassword = AppPreferences.getPassword(this);
        }

        startForeground(NOTIFICATION_ID, createNotification());
        startHotspot();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    public void setCallback(HotspotCallback callback) {
        this.callback = callback;
    }

    public void startHotspot() {
        hotspotManager.startHotspot(desiredSsid, desiredPassword);
    }

    public void stopHotspot() {
        hotspotManager.stopHotspot();
    }

    public boolean isHotspotStarted() {
        return hotspotManager.isStarted();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Hotspot Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains WiFi Hotspot functionality");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, HotspotService.class);
        stopIntent.setAction("STOP");
        PendingIntent stopPending = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent exitIntent = new Intent(this, AppExitReceiver.class);
        exitIntent.setAction(AppExitReceiver.ACTION_EXIT_APP);
        PendingIntent exitPending = PendingIntent.getBroadcast(
            this, 1, exitIntent, PendingIntent.FLAG_IMMUTABLE);

        Intent mainIntent = new Intent(this, MainActivity.class);
        PendingIntent mainPending = PendingIntent.getActivity(
            this, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        return builder
            .setContentTitle("WiFi Hotspot Active")
            .setContentText("Other devices can connect to share internet")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPending)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        hotspotManager.stopHotspot();
        super.onDestroy();
    }
}
