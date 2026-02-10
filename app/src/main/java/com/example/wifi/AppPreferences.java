package com.example.wifi;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.security.SecureRandom;

public final class AppPreferences {
    private static final String PREFS = "wifi_router_prefs";
    private static final String KEY_SSID = "ssid";
    private static final String KEY_PASSWORD = "password";
    private static final String KEY_PROXY_PORT = "proxy_port";
    private static final String KEY_KEEP_RUNNING = "keep_running";
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

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

    public static boolean getKeepRunning(Context context) {
        return getPrefs(context).getBoolean(KEY_KEEP_RUNNING, true);
    }

    public static void saveKeepRunning(Context context, boolean keep) {
        getPrefs(context).edit().putBoolean(KEY_KEEP_RUNNING, keep).apply();
    }

    public static void ensureDefaultHotspotConfig(Context context) {
        if (TextUtils.isEmpty(getSsid(context))) {
            saveSsid(context, generateSsid());
        }
        if (TextUtils.isEmpty(getPassword(context))) {
            savePassword(context, generatePassword());
        }
    }

    private static String generateSsid() {
        return "WiFi-" + randomString(6);
    }

    private static String generatePassword() {
        return randomString(12);
    }

    private static String randomString(int length) {
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int idx = RANDOM.nextInt(PASSWORD_CHARS.length());
            builder.append(PASSWORD_CHARS.charAt(idx));
        }
        return builder.toString();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
