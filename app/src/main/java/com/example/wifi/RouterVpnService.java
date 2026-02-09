package com.example.wifi;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * VPN Service that captures network traffic and routes it through the mobile network.
 * Acts as a software router for devices connected to the local-only hotspot.
 */
public class RouterVpnService extends VpnService {
    private static final String TAG = "RouterVpnService";
    private static final String CHANNEL_ID = "wifi_router_channel";
    private static final int NOTIFICATION_ID = 1;

    // VPN configuration
    private static final String VPN_ADDRESS = "10.0.0.1";
    private static final String VPN_ROUTE = "0.0.0.0";
    private static final int VPN_PREFIX = 0;
    private static final int MTU = 1500;

    private ParcelFileDescriptor vpnInterface;
    private ExecutorService executor;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    
    private static RouterVpnService instance;
    private StatusCallback statusCallback;

    public interface StatusCallback {
        void onPacketForwarded(int bytes);
        void onLog(String message);
    }

    public static RouterVpnService getInstance() {
        return instance;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) {
            stopVpn();
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification());
        startVpn();
        return START_STICKY;
    }

    private void startVpn() {
        if (isRunning.get()) {
            Log.w(TAG, "VPN already running");
            return;
        }

        try {
            Builder builder = new Builder();
            builder.setSession("WiFi Router")
                   .addAddress(VPN_ADDRESS, 24)
                   .addRoute(VPN_ROUTE, VPN_PREFIX)
                   .addDnsServer("8.8.8.8")
                   .addDnsServer("8.8.4.4")
                   .setMtu(MTU)
                   .setBlocking(true);

            vpnInterface = builder.establish();
            
            if (vpnInterface == null) {
                log("Failed to establish VPN interface");
                return;
            }

            isRunning.set(true);
            executor = Executors.newFixedThreadPool(2);
            
            // Start packet forwarding threads
            executor.submit(this::forwardOutgoing);
            
            log("VPN started successfully");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start VPN", e);
            log("VPN start failed: " + e.getMessage());
        }
    }

    private void forwardOutgoing() {
        FileInputStream in = new FileInputStream(vpnInterface.getFileDescriptor());
        FileOutputStream out = new FileOutputStream(vpnInterface.getFileDescriptor());
        
        ByteBuffer packet = ByteBuffer.allocate(MTU);
        
        while (isRunning.get()) {
            try {
                packet.clear();
                int length = in.read(packet.array());
                
                if (length > 0) {
                    packet.limit(length);
                    handlePacket(packet, out);
                }
                
            } catch (IOException e) {
                if (isRunning.get()) {
                    Log.e(TAG, "Error reading packet", e);
                }
                break;
            }
        }
    }

    private void handlePacket(ByteBuffer packet, FileOutputStream out) {
        try {
            int version = (packet.get(0) >> 4) & 0xF;
            
            if (version == 4) {
                handleIPv4Packet(packet, out);
            } else if (version == 6) {
                // IPv6 - pass through
                log("IPv6 packet received (not yet supported)");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling packet", e);
        }
    }

    private void handleIPv4Packet(ByteBuffer packet, FileOutputStream out) throws IOException {
        int headerLength = (packet.get(0) & 0x0F) * 4;
        int totalLength = ((packet.get(2) & 0xFF) << 8) | (packet.get(3) & 0xFF);
        int protocol = packet.get(9) & 0xFF;
        
        byte[] srcAddr = new byte[4];
        byte[] dstAddr = new byte[4];
        packet.position(12);
        packet.get(srcAddr);
        packet.get(dstAddr);
        
        InetAddress srcIp = InetAddress.getByAddress(srcAddr);
        InetAddress dstIp = InetAddress.getByAddress(dstAddr);

        if (protocol == 17) { // UDP
            handleUdpPacket(packet, headerLength, dstIp, out);
        } else if (protocol == 6) { // TCP  
            handleTcpPacket(packet, headerLength, dstIp, out);
        } else if (protocol == 1) { // ICMP
            handleIcmpPacket(packet, headerLength, dstIp, out);
        }
        
        if (statusCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                statusCallback.onPacketForwarded(totalLength));
        }
    }

    private void handleUdpPacket(ByteBuffer packet, int ipHeaderLen, InetAddress dstIp, FileOutputStream out) {
        try {
            int srcPort = ((packet.get(ipHeaderLen) & 0xFF) << 8) | (packet.get(ipHeaderLen + 1) & 0xFF);
            int dstPort = ((packet.get(ipHeaderLen + 2) & 0xFF) << 8) | (packet.get(ipHeaderLen + 3) & 0xFF);
            int udpLength = ((packet.get(ipHeaderLen + 4) & 0xFF) << 8) | (packet.get(ipHeaderLen + 5) & 0xFF);
            
            int dataOffset = ipHeaderLen + 8;
            int dataLength = udpLength - 8;
            
            if (dataLength > 0 && dataOffset + dataLength <= packet.limit()) {
                byte[] data = new byte[dataLength];
                packet.position(dataOffset);
                packet.get(data);
                
                // Forward via UDP
                DatagramChannel channel = DatagramChannel.open();
                protect(channel.socket());
                channel.connect(new InetSocketAddress(dstIp, dstPort));
                channel.write(ByteBuffer.wrap(data));
                
                // Read response
                ByteBuffer response = ByteBuffer.allocate(MTU);
                channel.configureBlocking(false);
                int read = channel.read(response);
                
                if (read > 0) {
                    response.flip();
                    byte[] responseData = new byte[read];
                    response.get(responseData);
                    
                    // Build response packet and write back
                    ByteBuffer responsePacket = buildUdpResponse(dstIp, packet, dstPort, srcPort, responseData);
                    out.write(responsePacket.array(), 0, responsePacket.limit());
                }
                
                channel.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "UDP forward error", e);
        }
    }

    private void handleTcpPacket(ByteBuffer packet, int ipHeaderLen, InetAddress dstIp, FileOutputStream out) {
        // TCP requires stateful connection tracking - simplified implementation
        try {
            int dstPort = ((packet.get(ipHeaderLen + 2) & 0xFF) << 8) | (packet.get(ipHeaderLen + 3) & 0xFF);
            log("TCP packet to " + dstIp.getHostAddress() + ":" + dstPort);
            
            // For full TCP support, would need to implement TCP state machine
            // or use a library like netty
            
        } catch (Exception e) {
            Log.e(TAG, "TCP handling error", e);
        }
    }

    private void handleIcmpPacket(ByteBuffer packet, int ipHeaderLen, InetAddress dstIp, FileOutputStream out) {
        try {
            int type = packet.get(ipHeaderLen) & 0xFF;
            if (type == 8) { // Echo request
                log("ICMP Echo request to " + dstIp.getHostAddress());
            }
        } catch (Exception e) {
            Log.e(TAG, "ICMP handling error", e);
        }
    }

    private ByteBuffer buildUdpResponse(InetAddress origDst, ByteBuffer origPacket, 
                                        int srcPort, int dstPort, byte[] data) {
        // Simplified response packet builder
        int totalLength = 20 + 8 + data.length; // IP + UDP + data
        ByteBuffer response = ByteBuffer.allocate(totalLength);
        
        // IP Header (simplified)
        response.put((byte) 0x45); // Version + IHL
        response.put((byte) 0x00); // TOS
        response.putShort((short) totalLength);
        response.putShort((short) 0); // ID
        response.putShort((short) 0x4000); // Flags + Fragment
        response.put((byte) 64); // TTL
        response.put((byte) 17); // Protocol UDP
        response.putShort((short) 0); // Checksum (would need to calculate)
        response.put(origDst.getAddress());
        response.put(new byte[]{10, 0, 0, 2}); // VPN client address
        
        // UDP Header
        response.putShort((short) srcPort);
        response.putShort((short) dstPort);
        response.putShort((short) (8 + data.length));
        response.putShort((short) 0); // Checksum
        
        // Data
        response.put(data);
        
        response.flip();
        return response;
    }

    public void stopVpn() {
        isRunning.set(false);
        
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        
        if (vpnInterface != null) {
            try {
                vpnInterface.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing VPN interface", e);
            }
            vpnInterface = null;
        }
        
        log("VPN stopped");
    }

    private void log(String message) {
        Log.i(TAG, message);
        AppLogBuffer.add(TAG, message);
        if (statusCallback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                statusCallback.onLog(message));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WiFi Router Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Maintains WiFi router functionality");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification createNotification() {
        Intent stopIntent = new Intent(this, RouterVpnService.class);
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
            .setContentTitle("WiFi Router Active")
            .setContentText("Routing traffic through mobile network")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(mainPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPending)
            .setOngoing(true)
            .build();
    }

    @Override
    public void onDestroy() {
        stopVpn();
        instance = null;
        super.onDestroy();
    }
}
