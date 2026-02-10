package com.example.wifi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class AppExitReceiver extends BroadcastReceiver {
    public static final String ACTION_EXIT_APP = "com.example.wifi.ACTION_EXIT_APP";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null && ACTION_EXIT_APP.equals(intent.getAction())) {
            stopAllServices(context);
            AppLogBuffer.add("App", "Exit requested");
        }
    }

    public static void stopAllServices(Context context) {
        context.stopService(new Intent(context, RouterVpnService.class));
        context.stopService(new Intent(context, HotspotService.class));
        context.stopService(new Intent(context, ProxyService.class));
    }
}
