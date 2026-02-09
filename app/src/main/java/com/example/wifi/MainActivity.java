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
import android.os.IBinder;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int VPN_REQUEST_CODE = 100;
    private static final int PERMISSION_REQUEST_CODE = 101;

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
                        ipText.setText("10.0.0.1");
                        appendLog("Hotspot started: " + ssid);
                        
                        // Now start VPN service
                        prepareVpn();
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
                        connectedDevicesLabel.setText("Data transferred: " + formatBytes(totalBytes));
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
    }

    private void stopRouter() {
        appendLog("Stopping router...");
        
        // Stop VPN service
        Intent vpnStopIntent = new Intent(this, RouterVpnService.class);
        vpnStopIntent.setAction("STOP");
        startService(vpnStopIntent);
        
        // Stop hotspot service
        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
        Intent hotspotStopIntent = new Intent(this, HotspotService.class);
        hotspotStopIntent.setAction("STOP");
        startService(hotspotStopIntent);
        
        isRouterActive = false;
        resetUI();
        appendLog("Router stopped");
    }

    private void resetUI() {
        statusText.setText("WiFi Router");
        statusText.setTextColor(0xFF000000); // Black
        ssidText.setText("---");
        passwordText.setText("---");
        ipText.setText("---");
        connectedDevicesLabel.setText("Connected Devices: 0");
        toggleButton.setText("Start Router");
        toggleButton.setEnabled(true);
        totalBytes = 0;
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
        super.onDestroy();
    }
}
