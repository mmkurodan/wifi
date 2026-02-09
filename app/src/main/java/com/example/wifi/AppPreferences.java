package com.example.wifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public final class AppPreferences {
    private static final String PREFS = "wifi_router_prefs";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_PROXY_PORT = "proxy_port";

    private AppPreferences() {
    }

    public static String getSsid(Context context) {
        return getPrefs(context).getString(KEY_SSID, "");
    }

    public static String getPassword(Context context) {
        return getPrefs(context).getString(KEY_PASSWORD, "");
    }

    public static int getProxyPort(Context context) {
        return getPrefs(context).getInt(KEY_PROXY_PORT, ProxyService.DEFAULT_PORT);
    }

    public static void saveSsid(Context context, String ssid) {
        getPrefs(context).edit().putString(KEY_SSID, ssid).apply();
    }

    public static void savePassword(Context context, String password) {
        getPrefs(context).edit().putString(KEY_PASSWORD, password).apply();
    }

    public static void saveProxyPort(Context context, int port) {
        getPrefs(context).edit().putInt(KEY_PROXY_PORT, port).apply();
    }

    public static void saveHotspotIfEmpty(Context context, String ssid, String password) {
        if (!TextUtils.isEmpty(ssid) && TextUtils.isEmpty(getSsid(context))) {
            saveSsid(context, ssid);
        }
        if (!TextUtils.isEmpty(password) && TextUtils.isEmpty(getPassword(context))) {
            savePassword(context, password);
        }
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
