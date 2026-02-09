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
import android.util.Log;

/**
 * Foreground service that manages the WiFi Hotspot.
 */
public class HotspotService extends Service {
    private static final String TAG = "HotspotService";
    private static final String CHANNEL_ID = "hotspot_channel";
    private static final int NOTIFICATION_ID = 2;

    private WifiHotspotManager hotspotManager;
    private final IBinder binder = new LocalBinder();
    private HotspotCallback callback;

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
                if (callback != null) {
                    callback.onHotspotStarted(ssid, password);
                }
            }

            @Override
            public void onStopped() {
                Log.i(TAG, "Hotspot stopped");
                if (callback != null) {
                    callback.onHotspotStopped();
                }
            }

            @Override
            public void onFailed(int reason) {
                Log.e(TAG, "Hotspot failed: " + reason);
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
        hotspotManager.startHotspot();
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
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        hotspotManager.stopHotspot();
        super.onDestroy();
    }
}
