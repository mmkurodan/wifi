package com.example.wifi;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.LocalOnlyHotspotCallback;
import android.net.wifi.WifiManager.LocalOnlyHotspotReservation;
import android.net.wifi.WifiConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Manages Local-only Hotspot for WiFi AP functionality.
 * Uses Android's LocalOnlyHotspot API (Android 8.0+).
 */
public class WifiHotspotManager {
    private static final String TAG = "WifiHotspotManager";

    private final Context context;
    private final WifiManager wifiManager;
    private LocalOnlyHotspotReservation reservation;
    private HotspotCallback callback;
    private boolean isStarted = false;

    public interface HotspotCallback {
        void onStarted(String ssid, String password);
        void onStopped();
        void onFailed(int reason);
    }

    public WifiHotspotManager(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    public void setCallback(HotspotCallback callback) {
        this.callback = callback;
    }

    public void startHotspot() {
        if (isStarted) {
            Log.w(TAG, "Hotspot already started");
            return;
        }

        if (wifiManager == null) {
            Log.e(TAG, "WifiManager not available");
            if (callback != null) {
                callback.onFailed(-1);
            }
            return;
        }

        try {
            wifiManager.startLocalOnlyHotspot(new LocalOnlyHotspotCallback() {
                @Override
                public void onStarted(LocalOnlyHotspotReservation res) {
                    reservation = res;
                    isStarted = true;
                    
                    String ssid = "";
                    String password = "";
                    
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // Android 11+
                        if (res.getSoftApConfiguration() != null) {
                            ssid = res.getSoftApConfiguration().getSsid();
                            password = res.getSoftApConfiguration().getPassphrase();
                        }
                    } else {
                        // Android 8-10
                        WifiConfiguration config = res.getWifiConfiguration();
                        if (config != null) {
                            ssid = config.SSID;
                            password = config.preSharedKey;
                        }
                    }
                    
                    Log.i(TAG, "Hotspot started - SSID: " + ssid);
                    
                    if (callback != null) {
                        callback.onStarted(ssid, password);
                    }
                }

                @Override
                public void onStopped() {
                    isStarted = false;
                    reservation = null;
                    Log.i(TAG, "Hotspot stopped");
                    
                    if (callback != null) {
                        callback.onStopped();
                    }
                }

                @Override
                public void onFailed(int reason) {
                    isStarted = false;
                    Log.e(TAG, "Hotspot failed with reason: " + reason);
                    
                    if (callback != null) {
                        callback.onFailed(reason);
                    }
                }
            }, new Handler(Looper.getMainLooper()));
            
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception starting hotspot", e);
            if (callback != null) {
                callback.onFailed(-2);
            }
        }
    }

    public void stopHotspot() {
        if (reservation != null) {
            reservation.close();
            reservation = null;
        }
        isStarted = false;
    }

    public boolean isStarted() {
        return isStarted;
    }
}
