package com.example.wifi;

import android.Manifest;
import android.app.Activity;
import android.app.NotificationManager;
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
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 101;
    private static final long DEVICE_REFRESH_MS = 3000;

    private TextView statusText;
    private EditText ssidInput;
    private EditText passwordInput;
    private TextView ipText;
    private TextView connectedDevicesLabel;
    private TextView logText;
    private ScrollView logScroll;
    private Button toggleButton;
    private EditText proxyPortInput;
    private Button saveButton;
    private Button exitButton;
    private Switch keepRunningSwitch;
    private Button notifSettingsButton;

    private HotspotService hotspotService;
    private boolean isServiceBound = false;
    private boolean isRouterActive = false;
    private long totalBytes = 0;
    private int connectedDevices = 0;
    private int proxyConnections = 0;

    private final Handler deviceHandler = new Handler(Looper.getMainLooper());
    private final Runnable deviceUpdater = new Runnable() {
        @Override
        public void run() {
            if (!isRouterActive) {
                refreshLogView();
                return;
            }
            int arpCount = getArpDeviceCount();
            int proxyCount = ProxyService.getActiveClientCount();
            updateConnectedDevices(arpCount, proxyCount);
            refreshLogView();
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
                        String hotspotIp = getHotspotIpAddress();
                        ipText.setText(hotspotIp);
                        AppPreferences.saveHotspotIfEmpty(MainActivity.this, ssid, password);
                        if (TextUtils.isEmpty(ssidInput.getText().toString()) && !TextUtils.isEmpty(ssid)) {
                            ssidInput.setText(ssid);
                        }
                        if (TextUtils.isEmpty(passwordInput.getText().toString()) && !TextUtils.isEmpty(password)) {
                            passwordInput.setText(password);
                        }

                        int proxyPort = getProxyPortFromInput();
                        appendLog("Hotspot started: " + ssid);
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && hasCustomHotspotConfig()) {
                            appendLog("Custom SSID/password cannot be applied on this Android version");
                        }
                        appendLog("Set client proxy to " + hotspotIp + ":" + proxyPort);
                        startProxyService(proxyPort);
                        isRouterActive = true;
                        setRouterActiveUi(true);
                        startDeviceUpdates();
                    });
                }

                @Override
                public void onHotspotStopped() {
                    runOnUiThread(() -> {
                        isRouterActive = false;
                        stopDeviceUpdates();
                        resetUI();
                        appendLog("Hotspot stopped");
                    });
                }

                @Override
                public void onHotspotFailed(int reason) {
                    runOnUiThread(() -> {
                        isRouterActive = false;
                        stopDeviceUpdates();
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

    @Override
    protected void onResume() {
        super.onResume();
        syncUiState();
        refreshLogView();
        checkNotificationEnabled();
    }

    private void initViews() {
        statusText = findViewById(R.id.statusText);
        ssidInput = findViewById(R.id.ssidInput);
        passwordInput = findViewById(R.id.passwordInput);
        ipText = findViewById(R.id.ipText);
        connectedDevicesLabel = findViewById(R.id.connectedDevicesLabel);
        logText = findViewById(R.id.logText);
        logScroll = findViewById(R.id.logScroll);
        toggleButton = findViewById(R.id.toggleButton);
        proxyPortInput = findViewById(R.id.proxyPortInput);
        saveButton = findViewById(R.id.saveButton);
        exitButton = findViewById(R.id.exitButton);
        keepRunningSwitch = findViewById(R.id.keepRunningSwitch);
        notifSettingsButton = findViewById(R.id.notifSettingsButton);

        toggleButton.setOnClickListener(v -> {
            if (isRouterActive) {
                stopRouter();
            } else {
                startRouter();
            }
        });

        saveButton.setOnClickListener(v -> saveSettings(true));
        exitButton.setOnClickListener(v -> exitApp());

        keepRunningSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppPreferences.saveKeepRunning(MainActivity.this, isChecked);
            appendLog("Keep running in background: " + isChecked);
        });

        notifSettingsButton.setOnClickListener(v -> openNotificationSettings());

        loadSettings();
        appendLog("WiFi Router initialized");
    }

    private void checkPermissions() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
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
            boolean notificationsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }

            for (int i = 0; i < permissions.length; i++) {
                if (Manifest.permission.POST_NOTIFICATIONS.equals(permissions[i])
                    && grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    notificationsGranted = false;
                }
            }
            
            if (!allGranted) {
                appendLog("Permissions denied - some features may not work");
                Toast.makeText(this, "Location permission is required for WiFi hotspot", 
                    Toast.LENGTH_LONG).show();
            } else {
                appendLog("All permissions granted");
            }

            if (!notificationsGranted) {
                appendLog("Notification permission denied - background status may not be visible");
            }
            checkNotificationEnabled();
        }
    }

    private void loadSettings() {
        AppPreferences.ensureDefaultHotspotConfig(this);
        String savedSsid = AppPreferences.getSsid(this);
        String savedPassword = AppPreferences.getPassword(this);
        int savedPort = AppPreferences.getProxyPort(this);

        if (!TextUtils.isEmpty(savedSsid)) {
            ssidInput.setText(savedSsid);
        }
        if (!TextUtils.isEmpty(savedPassword)) {
            passwordInput.setText(savedPassword);
        }
        proxyPortInput.setText(String.valueOf(savedPort));
        keepRunningSwitch.setChecked(AppPreferences.getKeepRunning(this));
    }

    private boolean saveSettings(boolean showToast) {
        String ssid = ssidInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        int port = parseProxyPort();

        if (!TextUtils.isEmpty(password) && password.length() < 8) {
            if (showToast) {
                Toast.makeText(this, "Password must be at least 8 characters", Toast.LENGTH_LONG).show();
            }
            return false;
        }

        if (!TextUtils.isEmpty(ssid)
            && TextUtils.isEmpty(password)
            && TextUtils.isEmpty(AppPreferences.getPassword(this))) {
            if (showToast) {
                Toast.makeText(this, "Password is required when setting a custom SSID", Toast.LENGTH_LONG).show();
            }
            return false;
        }

        if (port <= 0) {
            if (showToast) {
                Toast.makeText(this, "Proxy port must be between 1 and 65535", Toast.LENGTH_LONG).show();
            }
            return false;
        }

        if (!TextUtils.isEmpty(ssid)) {
            AppPreferences.saveSsid(this, ssid);
        }
        if (!TextUtils.isEmpty(password)) {
            AppPreferences.savePassword(this, password);
        }
        AppPreferences.saveProxyPort(this, port);

        if (showToast) {
            appendLog("Settings saved");
        }
        return true;
    }

    private int parseProxyPort() {
        String value = proxyPortInput.getText().toString().trim();
        if (TextUtils.isEmpty(value)) {
            return AppPreferences.getProxyPort(this);
        }
        try {
            int port = Integer.parseInt(value);
            if (port >= 1 && port <= 65535) {
                return port;
            }
        } catch (NumberFormatException ignored) {
            // Invalid number
        }
        return -1;
    }

    private int getProxyPortFromInput() {
        int port = parseProxyPort();
        if (port <= 0) {
            port = AppPreferences.getProxyPort(this);
        }
        return port;
    }

    private String getDesiredSsid() {
        String ssid = ssidInput.getText().toString().trim();
        if (TextUtils.isEmpty(ssid)) {
            ssid = AppPreferences.getSsid(this);
        }
        return ssid;
    }

    private String getDesiredPassword() {
        String password = passwordInput.getText().toString();
        if (TextUtils.isEmpty(password)) {
            password = AppPreferences.getPassword(this);
        }
        return password;
    }

    private boolean hasCustomHotspotConfig() {
        return !TextUtils.isEmpty(getDesiredSsid()) || !TextUtils.isEmpty(getDesiredPassword());
    }

    private void setRouterActiveUi(boolean active) {
        if (active) {
            statusText.setText("WiFi Router Active");
            statusText.setTextColor(0xFF4CAF50); // Green
            toggleButton.setText("Stop Router");
            toggleButton.setEnabled(true);
        } else {
            statusText.setText("WiFi Router");
            statusText.setTextColor(0xFF000000); // Black
            toggleButton.setText("Start Router");
            toggleButton.setEnabled(true);
        }
    }

    private void syncUiState() {
        boolean running = ProxyService.isRunning();
        isRouterActive = running;
        if (running) {
            setRouterActiveUi(true);
            ipText.setText(getHotspotIpAddress());
            startDeviceUpdates();
        } else {
            setRouterActiveUi(false);
            stopDeviceUpdates();
        }
        updateConnectedDevicesLabel();
    }

    private void exitApp() {
        appendLog("Exiting app...");
        AppExitReceiver.stopAllServices(this);
        isRouterActive = false;
        stopDeviceUpdates();
        setRouterActiveUi(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
    }

    private void openNotificationSettings() {
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
        }
        startActivity(intent);
    }

    private void checkNotificationEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && !manager.areNotificationsEnabled()) {
                appendLog("Notifications are disabled. Enable them to keep status visible.");
            }
        }
    }

    private void startRouter() {
        appendLog("Starting router...");
        toggleButton.setEnabled(false);
        toggleButton.setText("Starting...");

        if (!saveSettings(true)) {
            toggleButton.setEnabled(true);
            toggleButton.setText("Start Router");
            return;
        }

        String ssid = getDesiredSsid();
        String password = getDesiredPassword();
        
        // Start hotspot service
        Intent serviceIntent = new Intent(this, HotspotService.class);
        serviceIntent.putExtra(HotspotService.EXTRA_SSID, ssid);
        serviceIntent.putExtra(HotspotService.EXTRA_PASSWORD, password);
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
            setRouterActiveUi(true);
            appendLog("Router is now active");
        });
        startDeviceUpdates();
    }

    private void stopRouter() {
        appendLog("Stopping router...");

        AppExitReceiver.stopAllServices(this);
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }

        isRouterActive = false;
        stopDeviceUpdates();
        resetUI();
        appendLog("Router stopped");
    }

    private void resetUI() {
        setRouterActiveUi(false);
        ipText.setText("---");
        totalBytes = 0;
        connectedDevices = 0;
        proxyConnections = 0;
        updateConnectedDevicesLabel();
    }

    private void appendLog(String message) {
        AppLogBuffer.add(TAG, message);
        refreshLogView();
        Log.i(TAG, message);
    }

    private void refreshLogView() {
        String text = AppLogBuffer.getText();
        logText.setText(text);
        if (logScroll != null) {
            logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void startDeviceUpdates() {
        deviceHandler.removeCallbacks(deviceUpdater);
        deviceHandler.post(deviceUpdater);
    }

    private void stopDeviceUpdates() {
        deviceHandler.removeCallbacks(deviceUpdater);
    }

    private void updateConnectedDevices(int arpCount, int proxyCount) {
        connectedDevices = arpCount;
        proxyConnections = proxyCount;
        runOnUiThread(this::updateConnectedDevicesLabel);
    }

    private void updateConnectedDevicesLabel() {
        int displayCount = Math.max(connectedDevices, proxyConnections);
        connectedDevicesLabel.setText(
            "Connected Devices: " + displayCount + " (proxy: " + proxyConnections + ")");
    }

    private int getArpDeviceCount() {
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

    private void startProxyService(int port) {
        Intent proxyIntent = new Intent(this, ProxyService.class);
        proxyIntent.putExtra(ProxyService.EXTRA_PORT, port);
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
