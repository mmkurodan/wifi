package com.example.wifi;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final long DEVICE_REFRESH_MS = 3000;

    private TextView statusText;
    private TextView ssidText;
    private TextView passwordText;
    private TextView ipText;
    private TextView connectedDevicesLabel;
    private TextView logText;
    private Button toggleButton;

    private HotspotService hotspotService;
    private boolean isServiceBound = false;
    private boolean isRouterActive = false;
    private long totalBytes = 0;
    private int connectedDevices = 0;

    private final Handler deviceHandler = new Handler(Looper.getMainLooper());
    private final Runnable deviceUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isRouterActive) {
                return;
            }
            int count = getConnectedDeviceCount();
            updateConnectedDevices(count);
            deviceHandler.postDelayed(this, DEVICE_REFRESH_MS);
        }
    };

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            HotspotService.LocalBinder binder = (HotspotService.LocalBinder) service;
            hotspotService = binder.getService();
            isServiceBound = true;
            
            hotspotService.setCallback(new HotspotService.HotspotCallback() {
                @Override
                public void onHotspotStarted(String ssid, String password) {
                    runOnUiThread(() -> {
                        ssidText.setText(ssid != null ? ssid : "N/A");
                        passwordText.setText(password != null ? password : "N/A");
                        String hotspotIp = getHotspotIpAddress();
                        ipText.setText(hotspotIp);
                        appendLog("Hotspot started: " + ssid);

                        appendLog("Set client proxy to " + hotspotIp + ":" + ProxyService.PROXY_PORT);
                        startProxyService();
                        startDeviceUpdates();
                    });
                }

                @Override
                public void onHotspotStopped() {
                    runOnUiThread(() -> {
                        resetUI();
                        appendLog("Hotspot stopped");
                    });
                }

                @Override
                public void onHotspotFailed(int reason) {
                    runOnUiThread(() -> {
                        appendLog("Hotspot failed: " + getFailureReason(reason));
                        Toast.makeText(MainActivity.this, 
                            "Hotspot failed: " + getFailureReason(reason), 
                            Toast.LENGTH_LONG).show();
                        resetUI();
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            hotspotService = null;
            isServiceBound = false;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        ssidText = findViewById(R.id.ssidText);
        passwordText = findViewById(R.id.passwordText);
        ipText = findViewById(R.id.ipText);
        connectedDevicesLabel = findViewById(R.id.connectedDevicesLabel);
        logText = findViewById(R.id.logText);
        toggleButton = findViewById(R.id.toggleButton);

        logText.setMovementMethod(new ScrollingMovementMethod());
        
        toggleButton.setOnClickListener(v -> {
            if (isRouterActive) {
                stopRouter();
            } else {
                startRouter();
            }
        });

        appendLog("WiFi Router initialized");
    }

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            };
        } else {
            permissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION
            };
        }

        boolean allGranted = true;
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }

        if (!allGranted) {
            requestPermissions(permissions, PERMISSION_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            
            if (!allGranted) {
                appendLog("Permissions denied - some features may not work");
                Toast.makeText(this, "Location permission is required for WiFi hotspot", 
                    Toast.LENGTH_LONG).show();
            } else {
                appendLog("All permissions granted");
            }
        }
    }

    private void startRouter() {
        appendLog("Starting router...");
        toggleButton.setEnabled(false);
        toggleButton.setText("Starting...");
        
        // Start hotspot service
        Intent serviceIntent = new Intent(this, HotspotService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void prepareVpn() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null) {
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        } else {
            startVpnService();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService();
            } else {
                appendLog("VPN permission denied");
                Toast.makeText(this, "VPN permission is required for internet sharing", 
                    Toast.LENGTH_LONG).show();
                stopRouter();
            }
        }
    }

    private void startVpnService() {
        appendLog("Starting VPN service...");
        
        Intent vpnServiceIntent = new Intent(this, RouterVpnService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(vpnServiceIntent);
        } else {
            startService(vpnServiceIntent);
        }

        // Set up VPN status callback
        RouterVpnService vpnService = RouterVpnService.getInstance();
        if (vpnService != null) {
            vpnService.setStatusCallback(new RouterVpnService.StatusCallback() {
                @Override
                public void onPacketForwarded(int bytes) {
                    totalBytes += bytes;
                    runOnUiThread(() -> {
                        updateConnectedDevicesLabel();
                    });
                }

                @Override
                public void onLog(String message) {
                    runOnUiThread(() -> appendLog(message));
                }
            });
        }

        isRouterActive = true;
        runOnUiThread(() -> {
            statusText.setText("WiFi Router Active");
            statusText.setTextColor(0xFF4CAF50); // Green
            toggleButton.setText("Stop Router");
            toggleButton.setEnabled(true);
            appendLog("Router is now active");
        });
        startDeviceUpdates();
    }

    private void stopRouter() {
        appendLog("Stopping router...");
        
        // Stop VPN service
        Intent vpnStopIntent = new Intent(this, RouterVpnService.class);
        vpnStopIntent.setAction("STOP");
        startService(vpnStopIntent);

        stopProxyService();
        
        // Stop hotspot service
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        Intent hotspotStopIntent = new Intent(this, HotspotService.class);
        hotspotStopIntent.setAction("STOP");
        startService(hotspotStopIntent);
        
        isRouterActive = false;
        stopDeviceUpdates();
        resetUI();
        appendLog("Router stopped");
    }

    private void resetUI() {
        statusText.setText("WiFi Router");
        statusText.setTextColor(0xFF000000); // Black
        ssidText.setText("---");
        passwordText.setText("---");
        ipText.setText("---");
        toggleButton.setText("Start Router");
        toggleButton.setEnabled(true);
        totalBytes = 0;
        connectedDevices = 0;
        updateConnectedDevicesLabel();
    }

    private void appendLog(String message) {
        String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String logEntry = "[" + timestamp + "] " + message + "\n";
        logText.append(logEntry);
        
        // Auto-scroll to bottom safely after layout is ready
        logText.post(() -> {
            if (logText.getLayout() != null) {
                int scrollAmount = logText.getLayout().getLineTop(logText.getLineCount()) - logText.getHeight();
                if (scrollAmount > 0) {
                    logText.scrollTo(0, scrollAmount);
                }
            }
        });
        
        Log.i(TAG, message);
    }

    private void startDeviceUpdates() {
        deviceHandler.removeCallbacks(deviceUpdater);
        deviceHandler.post(deviceUpdater);
    }

    private void stopDeviceUpdates() {
        deviceHandler.removeCallbacks(deviceUpdater);
    }

    private void updateConnectedDevices(int count) {
        connectedDevices = count;
        runOnUiThread(this::updateConnectedDevicesLabel);
    }

    private void updateConnectedDevicesLabel() {
        connectedDevicesLabel.setText("Connected Devices: " + connectedDevices);
    }

    private int getConnectedDeviceCount() {
        int count = 0;
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line = reader.readLine(); // Skip header
            while ((line = reader.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 6) {
                    String flags = parts[2];
                    String mac = parts[3];
                    String device = parts[5];
                    if ("0x2".equals(flags)
                        && mac != null
                        && !"00:00:00:00:00:00".equals(mac)
                        && isHotspotInterface(device)) {
                        count++;
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Failed to read /proc/net/arp", e);
        }
        return count;
    }

    private boolean isHotspotInterface(String device) {
        return device != null
            && (device.startsWith("wlan")
                || device.startsWith("ap")
                || device.startsWith("swlan")
                || device.startsWith("wifi"));
    }

    private String getHotspotIpAddress() {
        try {
            for (NetworkInterface intf : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (isHotspotInterface(intf.getName())) {
                    for (InetAddress addr : Collections.list(intf.getInetAddresses())) {
                        if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.w(TAG, "Failed to read hotspot IP", e);
        }
        return "192.168.43.1";
    }

    private void startProxyService() {
        Intent proxyIntent = new Intent(this, ProxyService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(proxyIntent);
        } else {
            startService(proxyIntent);
        }
    }

    private void stopProxyService() {
        Intent proxyStopIntent = new Intent(this, ProxyService.class);
        proxyStopIntent.setAction("STOP");
        startService(proxyStopIntent);
    }

    private String getFailureReason(int reason) {
        switch (reason) {
            case 0: return "No error";
            case 1: return "No channel available";
            case 2: return "Incompatible mode";
            case -1: return "WifiManager not available";
            case -2: return "Security exception";
            default: return "Unknown error (" + reason + ")";
        }
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        else if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        else if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        else return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    protected void onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        stopDeviceUpdates();
        super.onDestroy();
    }
}
