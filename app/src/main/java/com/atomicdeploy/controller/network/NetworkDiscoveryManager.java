package com.atomicdeploy.controller.network;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * NetworkDiscoveryManager - Comprehensive network device discovery using multiple protocols.
 * 
 * This class implements device discovery using:
 * - ARP (Address Resolution Protocol) table scanning
 * - mDNS (Multicast DNS) / Bonjour
 * - NSD (Network Service Discovery) / DNS-SD
 * - UDP Broadcast
 * - SSDP/UPnP (Simple Service Discovery Protocol)
 * - MNDP (MikroTik Neighbor Discovery Protocol)
 * - NetBIOS Name Service
 * 
 * ================================================================================
 * DEVICE IMPLEMENTATION GUIDE
 * ================================================================================
 * 
 * For devices to be discovered by this app, they should implement one or more
 * of the following protocols. Below are detailed specifications for each.
 * 
 * --------------------------------------------------------------------------------
 * 1. NSD (DNS-SD) - Network Service Discovery
 * --------------------------------------------------------------------------------
 * 
 * DNS-SD uses standard DNS record types to advertise services. Devices should
 * register a service with the following characteristics:
 * 
 * SERVICE TYPE: "_controller._tcp" or "_http._tcp" (for web interfaces)
 * 
 * Required TXT Records (key=value pairs):
 *   - deviceName=<human-readable name>     Example: "Living Room Controller"
 *   - deviceType=<type identifier>         Example: "smart_switch", "sensor", "controller"
 *   - firmwareVersion=<version string>     Example: "1.2.3"
 *   - manufacturer=<company name>          Example: "AtomicDeploy"
 *   - macAddress=<MAC in format>           Example: "AA:BB:CC:DD:EE:FF"
 *   - protocol=<supported protocols>       Example: "http,mqtt,modbus"
 * 
 * Optional TXT Records:
 *   - model=<model identifier>             Example: "CTL-2000"
 *   - serialNumber=<serial>                Example: "SN123456789"
 *   - apiVersion=<API version>             Example: "v2"
 *   - capabilities=<comma-separated>       Example: "temperature,humidity,relay"
 *   - configUrl=<configuration URL path>   Example: "/config"
 *   - status=<current status>              Example: "online", "standby"
 * 
 * Example mDNS/DNS-SD Registration (pseudocode):
 * 
 *   ServiceInfo info = new ServiceInfo(
 *       "_controller._tcp.local.",    // Service type
 *       "MyDevice",                    // Service name
 *       80,                            // Port number
 *       "deviceName=My Smart Device"  // TXT record
 *   );
 *   jmdns.registerService(info);
 * 
 * The device should respond to PTR, SRV, TXT, and A/AAAA queries.
 * 
 * --------------------------------------------------------------------------------
 * 2. UDP BROADCAST - Custom Discovery Protocol
 * --------------------------------------------------------------------------------
 * 
 * The app sends discovery broadcasts on port 5353 (mDNS) and a custom port 9999.
 * 
 * DISCOVERY REQUEST FORMAT (sent by app):
 * 
 *   Bytes 0-3:   Magic header "CTRL" (0x4354524C)
 *   Byte 4:      Protocol version (0x01)
 *   Byte 5:      Message type (0x01 = Discovery Request)
 *   Bytes 6-7:   Reserved (0x0000)
 *   Bytes 8-15:  Timestamp (Unix epoch milliseconds, big-endian)
 * 
 * DISCOVERY RESPONSE FORMAT (sent by device):
 * 
 *   Bytes 0-3:   Magic header "CTRL" (0x4354524C)
 *   Byte 4:      Protocol version (0x01)
 *   Byte 5:      Message type (0x02 = Discovery Response)
 *   Bytes 6-7:   Payload length (big-endian)
 *   Bytes 8+:    JSON payload
 * 
 * JSON Payload Structure:
 * {
 *     "macAddress": "AA:BB:CC:DD:EE:FF",
 *     "ipAddress": "192.168.1.100",
 *     "hostname": "device-hostname",
 *     "deviceName": "Friendly Device Name",
 *     "deviceType": "controller",
 *     "manufacturer": "AtomicDeploy",
 *     "firmwareVersion": "1.2.3",
 *     "model": "CTL-2000",
 *     "capabilities": ["http", "mqtt", "relay"],
 *     "port": 80,
 *     "apiVersion": "v2",
 *     "uptime": 123456,
 *     "status": "online"
 * }
 * 
 * Required fields: macAddress, ipAddress, deviceName
 * All other fields are optional but recommended.
 * 
 * Device Implementation Example (C/Arduino pseudocode):
 * 
 *   void handleDiscoveryRequest(UDP& udp, IPAddress remoteIP, uint16_t remotePort) {
 *       // Verify magic header
 *       if (udp.read() != 'C' || udp.read() != 'T' || 
 *           udp.read() != 'R' || udp.read() != 'L') return;
 *       
 *       // Verify message type is discovery request
 *       uint8_t version = udp.read();
 *       uint8_t msgType = udp.read();
 *       if (msgType != 0x01) return;
 *       
 *       // Build response
 *       String json = buildDeviceInfoJson();
 *       sendDiscoveryResponse(udp, remoteIP, remotePort, json);
 *   }
 * 
 * --------------------------------------------------------------------------------
 * 3. SSDP/UPnP Discovery
 * --------------------------------------------------------------------------------
 * 
 * Devices should listen on 239.255.255.250:1900 for M-SEARCH requests.
 * Respond with device information in standard SSDP format.
 * 
 * --------------------------------------------------------------------------------
 * 4. MNDP (MikroTik Neighbor Discovery Protocol)
 * --------------------------------------------------------------------------------
 * 
 * Uses UDP multicast on 255.255.255.255:5678.
 * TLV-encoded messages with device information.
 * 
 * ================================================================================
 */
public class NetworkDiscoveryManager {
    
    private static final String TAG = "NetworkDiscovery";
    
    // Discovery ports and addresses
    private static final int MDNS_PORT = 5353;
    private static final int SSDP_PORT = 1900;
    private static final int MNDP_PORT = 5678;
    private static final int NETBIOS_PORT = 137;
    private static final int CUSTOM_DISCOVERY_PORT = 9999;
    
    private static final String MDNS_ADDRESS = "224.0.0.251";
    private static final String SSDP_ADDRESS = "239.255.255.250";
    
    // Discovery protocol magic bytes
    private static final byte[] DISCOVERY_MAGIC = {'C', 'T', 'R', 'L'};
    private static final byte PROTOCOL_VERSION = 0x01;
    private static final byte MSG_DISCOVERY_REQUEST = 0x01;
    private static final byte MSG_DISCOVERY_RESPONSE = 0x02;
    
    // NSD Service types to discover
    private static final String[] NSD_SERVICE_TYPES = {
        "_controller._tcp.",
        "_http._tcp.",
        "_https._tcp.",
        "_ssh._tcp.",
        "_telnet._tcp.",
        "_ftp._tcp.",
        "_smb._tcp.",
        "_nfs._tcp.",
        "_mqtt._tcp.",
        "_coap._udp.",
        "_hap._tcp.",           // HomeKit
        "_googlecast._tcp.",    // Chromecast
        "_spotify-connect._tcp.",
        "_airplay._tcp.",
        "_raop._tcp.",          // AirPlay
        "_printer._tcp.",
        "_ipp._tcp.",           // Internet Printing Protocol
        "_scanner._tcp.",
        "_workstation._tcp.",
        "_device-info._tcp."
    };
    
    private final Context context;
    private final ExecutorService executorService;
    private final Handler mainHandler;
    private final ConcurrentHashMap<String, Map<String, String>> discoveredDevices;
    private final List<DiscoveryListener> listeners;
    
    private NsdManager nsdManager;
    private WifiManager.MulticastLock multicastLock;
    private volatile boolean isDiscovering = false;
    
    public interface DiscoveryListener {
        void onDeviceFound(Map<String, String> deviceInfo);
        void onDeviceUpdated(Map<String, String> deviceInfo);
        void onDeviceLost(String macAddress);
        void onDiscoveryStarted();
        void onDiscoveryFinished();
        void onError(String message);
    }
    
    public NetworkDiscoveryManager(Context context) {
        this.context = context.getApplicationContext();
        this.executorService = Executors.newFixedThreadPool(8);
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.discoveredDevices = new ConcurrentHashMap<>();
        this.listeners = Collections.synchronizedList(new ArrayList<>());
        
        this.nsdManager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        
        WifiManager wifiManager = (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            multicastLock = wifiManager.createMulticastLock("ControllerDiscovery");
            multicastLock.setReferenceCounted(false);
        }
    }
    
    public void addListener(DiscoveryListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }
    
    public void removeListener(DiscoveryListener listener) {
        listeners.remove(listener);
    }
    
    /**
     * Start comprehensive network discovery using all available methods.
     */
    public void startDiscovery() {
        if (isDiscovering) {
            Log.w(TAG, "Discovery already in progress");
            return;
        }
        
        isDiscovering = true;
        notifyDiscoveryStarted();
        
        // Acquire multicast lock for mDNS/SSDP
        if (multicastLock != null && !multicastLock.isHeld()) {
            multicastLock.acquire();
        }
        
        // Start all discovery methods in parallel
        executorService.submit(this::discoverViaArp);
        executorService.submit(this::discoverViaNsd);
        executorService.submit(this::discoverViaUdpBroadcast);
        executorService.submit(this::discoverViaSsdp);
        executorService.submit(this::discoverViaMndp);
        executorService.submit(this::discoverViaNetBios);
        
        // Schedule discovery completion
        mainHandler.postDelayed(() -> {
            isDiscovering = false;
            notifyDiscoveryFinished();
        }, 15000); // 15 seconds total discovery time
    }
    
    /**
     * Stop all discovery processes.
     */
    public void stopDiscovery() {
        isDiscovering = false;
        
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        
        stopNsdDiscovery();
    }
    
    /**
     * Get all currently discovered devices.
     */
    public List<Map<String, String>> getDiscoveredDevices() {
        return new ArrayList<>(discoveredDevices.values());
    }
    
    /**
     * Clear all discovered devices.
     */
    public void clearDiscoveredDevices() {
        discoveredDevices.clear();
    }
    
    // ============================================================================
    // ARP Table Discovery
    // ============================================================================
    
    /**
     * Discover devices by reading the ARP cache table.
     * This method reads /proc/net/arp to find devices on the local network.
     */
    private void discoverViaArp() {
        Log.d(TAG, "Starting ARP table discovery...");
        
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/net/arp"))) {
            String line;
            boolean isFirstLine = true;
            
            while ((line = reader.readLine()) != null) {
                // Skip header line
                if (isFirstLine) {
                    isFirstLine = false;
                    continue;
                }
                
                // Parse ARP entry: IP address, HW type, Flags, HW address, Mask, Device
                String[] parts = line.split("\\s+");
                if (parts.length >= 4) {
                    String ipAddress = parts[0];
                    String macAddress = parts[3].toUpperCase();
                    
                    // Skip incomplete entries (00:00:00:00:00:00)
                    if (macAddress.equals("00:00:00:00:00:00") || 
                        macAddress.contains("INCOMPLETE")) {
                        continue;
                    }
                    
                    Map<String, String> deviceInfo = new HashMap<>();
                    deviceInfo.put("macAddress", macAddress);
                    deviceInfo.put("ipAddress", ipAddress);
                    deviceInfo.put("discoveryMethod", "ARP");
                    deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
                    
                    // Try to resolve hostname
                    resolveHostname(deviceInfo);
                    
                    // Try to identify device type from MAC vendor
                    identifyDeviceFromMac(deviceInfo);
                    
                    addOrUpdateDevice(deviceInfo);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading ARP table", e);
            notifyError("Failed to read ARP table: " + e.getMessage());
        }
        
        Log.d(TAG, "ARP discovery completed");
    }
    
    // ============================================================================
    // NSD (DNS-SD) Discovery
    // ============================================================================
    
    private final List<NsdManager.DiscoveryListener> activeNsdListeners = new ArrayList<>();
    
    /**
     * Discover devices using Network Service Discovery (DNS-SD).
     * 
     * This discovers services advertised via mDNS/DNS-SD. Devices should register
     * their services using the service types defined in NSD_SERVICE_TYPES.
     */
    private void discoverViaNsd() {
        Log.d(TAG, "Starting NSD (DNS-SD) discovery...");
        
        for (String serviceType : NSD_SERVICE_TYPES) {
            try {
                NsdManager.DiscoveryListener discoveryListener = new NsdManager.DiscoveryListener() {
                    @Override
                    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                        Log.e(TAG, "NSD discovery failed for " + serviceType + ": " + errorCode);
                    }
                    
                    @Override
                    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                        Log.e(TAG, "NSD stop discovery failed: " + errorCode);
                    }
                    
                    @Override
                    public void onDiscoveryStarted(String serviceType) {
                        Log.d(TAG, "NSD discovery started for: " + serviceType);
                    }
                    
                    @Override
                    public void onDiscoveryStopped(String serviceType) {
                        Log.d(TAG, "NSD discovery stopped for: " + serviceType);
                    }
                    
                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        Log.d(TAG, "NSD service found: " + serviceInfo.getServiceName());
                        resolveNsdService(serviceInfo);
                    }
                    
                    @Override
                    public void onServiceLost(NsdServiceInfo serviceInfo) {
                        Log.d(TAG, "NSD service lost: " + serviceInfo.getServiceName());
                    }
                };
                
                synchronized (activeNsdListeners) {
                    activeNsdListeners.add(discoveryListener);
                }
                
                nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting NSD discovery for " + serviceType, e);
            }
        }
    }
    
    private void resolveNsdService(NsdServiceInfo serviceInfo) {
        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo serviceInfo, int errorCode) {
                Log.e(TAG, "NSD resolve failed: " + errorCode);
            }
            
            @Override
            public void onServiceResolved(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "NSD service resolved: " + serviceInfo);
                
                Map<String, String> deviceInfo = new HashMap<>();
                
                InetAddress host = serviceInfo.getHost();
                if (host != null) {
                    deviceInfo.put("ipAddress", host.getHostAddress());
                    deviceInfo.put("hostname", host.getHostName());
                }
                
                deviceInfo.put("serviceName", serviceInfo.getServiceName());
                deviceInfo.put("serviceType", serviceInfo.getServiceType());
                deviceInfo.put("port", String.valueOf(serviceInfo.getPort()));
                deviceInfo.put("discoveryMethod", "NSD");
                deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
                
                // Parse TXT records for additional device info
                Map<String, byte[]> attributes = serviceInfo.getAttributes();
                if (attributes != null) {
                    for (Map.Entry<String, byte[]> entry : attributes.entrySet()) {
                        String key = entry.getKey();
                        byte[] valueBytes = entry.getValue();
                        String value = valueBytes != null ? 
                            new String(valueBytes, StandardCharsets.UTF_8) : "";
                        
                        // Map TXT record keys to our device info keys
                        switch (key.toLowerCase()) {
                            case "devicename":
                                deviceInfo.put("deviceName", value);
                                break;
                            case "devicetype":
                                deviceInfo.put("deviceType", value);
                                break;
                            case "macaddress":
                            case "mac":
                                deviceInfo.put("macAddress", value.toUpperCase());
                                break;
                            case "firmwareversion":
                            case "firmware":
                            case "fw":
                                deviceInfo.put("firmwareVersion", value);
                                break;
                            case "manufacturer":
                            case "mfg":
                                deviceInfo.put("manufacturer", value);
                                break;
                            case "model":
                                deviceInfo.put("model", value);
                                break;
                            default:
                                deviceInfo.put("txt_" + key, value);
                                break;
                        }
                    }
                }
                
                // If no device name, use service name
                if (!deviceInfo.containsKey("deviceName")) {
                    deviceInfo.put("deviceName", serviceInfo.getServiceName());
                }
                
                // Generate MAC if not provided (use IP-based pseudo-MAC)
                if (!deviceInfo.containsKey("macAddress") && deviceInfo.containsKey("ipAddress")) {
                    deviceInfo.put("macAddress", generatePseudoMac(deviceInfo.get("ipAddress")));
                }
                
                addOrUpdateDevice(deviceInfo);
            }
        });
    }
    
    private void stopNsdDiscovery() {
        synchronized (activeNsdListeners) {
            for (NsdManager.DiscoveryListener listener : activeNsdListeners) {
                try {
                    nsdManager.stopServiceDiscovery(listener);
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping NSD discovery", e);
                }
            }
            activeNsdListeners.clear();
        }
    }
    
    // ============================================================================
    // UDP Broadcast Discovery
    // ============================================================================
    
    /**
     * Discover devices using UDP broadcast.
     * 
     * PROTOCOL SPECIFICATION:
     * 
     * Request Format (16 bytes):
     *   Bytes 0-3:   Magic "CTRL" (0x4354524C)
     *   Byte 4:      Version (0x01)
     *   Byte 5:      Message Type (0x01 = Discovery Request)
     *   Bytes 6-7:   Reserved (0x0000)
     *   Bytes 8-15:  Timestamp (Unix milliseconds, big-endian)
     * 
     * Response Format (8 + N bytes):
     *   Bytes 0-3:   Magic "CTRL" (0x4354524C)
     *   Byte 4:      Version (0x01)
     *   Byte 5:      Message Type (0x02 = Discovery Response)
     *   Bytes 6-7:   Payload Length (N, big-endian)
     *   Bytes 8+:    JSON payload (UTF-8)
     * 
     * Expected JSON fields in response:
     *   - macAddress (required): Device MAC address "AA:BB:CC:DD:EE:FF"
     *   - ipAddress (required): Device IP address
     *   - deviceName (required): Human-readable device name
     *   - hostname: Network hostname
     *   - deviceType: Type identifier (e.g., "controller", "sensor")
     *   - manufacturer: Manufacturer name
     *   - firmwareVersion: Firmware version string
     *   - model: Device model
     *   - capabilities: Array of capability strings
     *   - port: Service port number
     *   - status: Current status
     */
    private void discoverViaUdpBroadcast() {
        Log.d(TAG, "Starting UDP broadcast discovery...");
        
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(5000);
            
            // Build discovery request packet
            ByteBuffer request = ByteBuffer.allocate(16);
            request.order(ByteOrder.BIG_ENDIAN);
            request.put(DISCOVERY_MAGIC);
            request.put(PROTOCOL_VERSION);
            request.put(MSG_DISCOVERY_REQUEST);
            request.putShort((short) 0); // Reserved
            request.putLong(System.currentTimeMillis());
            
            byte[] requestData = request.array();
            
            // Send to broadcast address
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(
                requestData, requestData.length, broadcastAddress, CUSTOM_DISCOVERY_PORT);
            socket.send(packet);
            
            Log.d(TAG, "UDP discovery request sent to port " + CUSTOM_DISCOVERY_PORT);
            
            // Also send to mDNS port for devices that listen there
            packet = new DatagramPacket(
                requestData, requestData.length, broadcastAddress, MDNS_PORT);
            socket.send(packet);
            
            // Listen for responses
            byte[] responseBuffer = new byte[4096];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            
            long endTime = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < endTime) {
                try {
                    socket.receive(responsePacket);
                    processUdpResponse(responsePacket);
                } catch (SocketTimeoutException e) {
                    // Continue waiting
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "UDP broadcast discovery error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        
        Log.d(TAG, "UDP broadcast discovery completed");
    }
    
    private void processUdpResponse(DatagramPacket packet) {
        try {
            byte[] data = packet.getData();
            int length = packet.getLength();
            
            if (length < 8) return;
            
            // Verify magic header
            if (data[0] != 'C' || data[1] != 'T' || data[2] != 'R' || data[3] != 'L') {
                return;
            }
            
            // Check message type
            if (data[5] != MSG_DISCOVERY_RESPONSE) {
                return;
            }
            
            // Get payload length
            int payloadLength = ((data[6] & 0xFF) << 8) | (data[7] & 0xFF);
            if (length < 8 + payloadLength) {
                return;
            }
            
            // Parse JSON payload
            String jsonPayload = new String(data, 8, payloadLength, StandardCharsets.UTF_8);
            Map<String, String> deviceInfo = parseJsonToMap(jsonPayload);
            
            if (deviceInfo.isEmpty()) {
                return;
            }
            
            // Add source IP if not in payload
            if (!deviceInfo.containsKey("ipAddress")) {
                deviceInfo.put("ipAddress", packet.getAddress().getHostAddress());
            }
            
            deviceInfo.put("discoveryMethod", "UDP");
            deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
            
            addOrUpdateDevice(deviceInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing UDP response", e);
        }
    }
    
    // ============================================================================
    // SSDP/UPnP Discovery
    // ============================================================================
    
    /**
     * Discover devices using SSDP (Simple Service Discovery Protocol).
     * This is part of the UPnP specification.
     */
    private void discoverViaSsdp() {
        Log.d(TAG, "Starting SSDP/UPnP discovery...");
        
        MulticastSocket socket = null;
        try {
            socket = new MulticastSocket(SSDP_PORT);
            socket.setReuseAddress(true);
            socket.setSoTimeout(5000);
            
            InetAddress ssdpAddress = InetAddress.getByName(SSDP_ADDRESS);
            socket.joinGroup(new InetSocketAddress(ssdpAddress, SSDP_PORT), 
                NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
            
            // SSDP M-SEARCH request
            String searchRequest = 
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 3\r\n" +
                "ST: ssdp:all\r\n" +
                "USER-AGENT: Controller/1.0\r\n" +
                "\r\n";
            
            byte[] requestData = searchRequest.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(
                requestData, requestData.length, ssdpAddress, SSDP_PORT);
            socket.send(packet);
            
            Log.d(TAG, "SSDP M-SEARCH sent");
            
            // Listen for responses
            byte[] responseBuffer = new byte[2048];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            
            long endTime = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < endTime) {
                try {
                    socket.receive(responsePacket);
                    processSsdpResponse(responsePacket);
                } catch (SocketTimeoutException e) {
                    // Continue
                }
            }
            
            socket.leaveGroup(new InetSocketAddress(ssdpAddress, SSDP_PORT),
                NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
            
        } catch (Exception e) {
            Log.e(TAG, "SSDP discovery error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        
        Log.d(TAG, "SSDP discovery completed");
    }
    
    private void processSsdpResponse(DatagramPacket packet) {
        try {
            String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
            
            Map<String, String> deviceInfo = new HashMap<>();
            deviceInfo.put("ipAddress", packet.getAddress().getHostAddress());
            deviceInfo.put("discoveryMethod", "SSDP");
            deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
            
            // Parse SSDP headers
            String[] lines = response.split("\r\n");
            for (String line : lines) {
                int colonIndex = line.indexOf(':');
                if (colonIndex > 0) {
                    String key = line.substring(0, colonIndex).trim().toUpperCase();
                    String value = line.substring(colonIndex + 1).trim();
                    
                    switch (key) {
                        case "SERVER":
                            deviceInfo.put("server", value);
                            // Try to extract device info from server string
                            if (value.contains("/")) {
                                String[] parts = value.split("/");
                                if (parts.length > 0) {
                                    deviceInfo.put("deviceName", parts[0].trim());
                                }
                            }
                            break;
                        case "LOCATION":
                            deviceInfo.put("location", value);
                            break;
                        case "USN":
                            deviceInfo.put("usn", value);
                            // Extract UUID from USN
                            if (value.contains("uuid:")) {
                                String uuid = value.substring(value.indexOf("uuid:") + 5);
                                if (uuid.contains("::")) {
                                    uuid = uuid.substring(0, uuid.indexOf("::"));
                                }
                                deviceInfo.put("uuid", uuid);
                            }
                            break;
                        case "ST":
                            deviceInfo.put("serviceType", value);
                            break;
                    }
                }
            }
            
            // Generate MAC from UUID or IP
            if (deviceInfo.containsKey("uuid")) {
                deviceInfo.put("macAddress", generateMacFromUuid(deviceInfo.get("uuid")));
            } else {
                deviceInfo.put("macAddress", generatePseudoMac(deviceInfo.get("ipAddress")));
            }
            
            addOrUpdateDevice(deviceInfo);
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing SSDP response", e);
        }
    }
    
    // ============================================================================
    // MNDP (MikroTik Neighbor Discovery Protocol)
    // ============================================================================
    
    /**
     * Discover devices using MNDP (MikroTik Neighbor Discovery Protocol).
     * 
     * MNDP uses UDP broadcast on port 5678 with TLV (Type-Length-Value) encoding.
     * Even non-MikroTik devices can implement this protocol for discovery.
     */
    private void discoverViaMndp() {
        Log.d(TAG, "Starting MNDP discovery...");
        
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(MNDP_PORT);
            socket.setBroadcast(true);
            socket.setSoTimeout(5000);
            socket.setReuseAddress(true);
            
            // MNDP discovery packet (minimal request)
            // Type: 0x0000 (Hello), Length: varies
            ByteBuffer request = ByteBuffer.allocate(4);
            request.order(ByteOrder.LITTLE_ENDIAN);
            request.putShort((short) 0x0000); // Type: Hello
            request.putShort((short) 0x0000); // Length: 0
            
            byte[] requestData = request.array();
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(
                requestData, requestData.length, broadcastAddress, MNDP_PORT);
            socket.send(packet);
            
            Log.d(TAG, "MNDP request sent");
            
            // Listen for MNDP responses
            byte[] responseBuffer = new byte[1500];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            
            long endTime = System.currentTimeMillis() + 5000;
            while (System.currentTimeMillis() < endTime) {
                try {
                    socket.receive(responsePacket);
                    processMndpResponse(responsePacket);
                } catch (SocketTimeoutException e) {
                    // Continue
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "MNDP discovery error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        
        Log.d(TAG, "MNDP discovery completed");
    }
    
    private void processMndpResponse(DatagramPacket packet) {
        try {
            byte[] data = packet.getData();
            int length = packet.getLength();
            
            if (length < 4) return;
            
            Map<String, String> deviceInfo = new HashMap<>();
            deviceInfo.put("ipAddress", packet.getAddress().getHostAddress());
            deviceInfo.put("discoveryMethod", "MNDP");
            deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
            
            // Parse TLV records
            ByteBuffer buffer = ByteBuffer.wrap(data, 0, length);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            
            while (buffer.remaining() >= 4) {
                int type = buffer.getShort() & 0xFFFF;
                int tlvLength = buffer.getShort() & 0xFFFF;
                
                if (buffer.remaining() < tlvLength) break;
                
                byte[] valueBytes = new byte[tlvLength];
                buffer.get(valueBytes);
                
                // MNDP TLV types
                switch (type) {
                    case 0x0001: // MAC Address
                        if (tlvLength == 6) {
                            deviceInfo.put("macAddress", formatMacAddress(valueBytes));
                        }
                        break;
                    case 0x0005: // Identity (hostname)
                        deviceInfo.put("hostname", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        deviceInfo.put("deviceName", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x0007: // Version
                        deviceInfo.put("firmwareVersion", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x0008: // Platform
                        deviceInfo.put("model", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x000A: // Uptime
                        if (tlvLength >= 4) {
                            ByteBuffer uptimeBuffer = ByteBuffer.wrap(valueBytes);
                            uptimeBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            int uptime = uptimeBuffer.getInt();
                            deviceInfo.put("uptime", String.valueOf(uptime));
                        }
                        break;
                    case 0x000B: // Software ID
                        deviceInfo.put("softwareId", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x000C: // Board
                        deviceInfo.put("board", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x000E: // IPv6 Address
                        // Parse IPv6 if needed
                        break;
                    case 0x000F: // Interface name
                        deviceInfo.put("interface", new String(valueBytes, StandardCharsets.UTF_8).trim());
                        break;
                    case 0x0010: // IPv4 Address
                        if (tlvLength >= 4) {
                            String ip = String.format("%d.%d.%d.%d",
                                valueBytes[0] & 0xFF, valueBytes[1] & 0xFF,
                                valueBytes[2] & 0xFF, valueBytes[3] & 0xFF);
                            deviceInfo.put("ipAddress", ip);
                        }
                        break;
                }
            }
            
            // Identify as MikroTik/RouterOS device
            deviceInfo.put("deviceType", "router");
            if (deviceInfo.containsKey("softwareId")) {
                deviceInfo.put("manufacturer", "MikroTik");
            }
            
            if (deviceInfo.containsKey("macAddress")) {
                addOrUpdateDevice(deviceInfo);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing MNDP response", e);
        }
    }
    
    // ============================================================================
    // NetBIOS Name Service Discovery
    // ============================================================================
    
    /**
     * Discover devices using NetBIOS Name Service.
     * Sends NBNS queries to discover Windows and Samba devices.
     */
    private void discoverViaNetBios() {
        Log.d(TAG, "Starting NetBIOS discovery...");
        
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(3000);
            
            // NetBIOS Name Query packet
            // Transaction ID: 0x0001
            // Flags: 0x0010 (broadcast)
            // Questions: 1
            // Query: * (wildcard)
            byte[] netbiosQuery = new byte[] {
                0x00, 0x01,  // Transaction ID
                0x00, 0x10,  // Flags: broadcast
                0x00, 0x01,  // Questions: 1
                0x00, 0x00,  // Answers: 0
                0x00, 0x00,  // Authority: 0
                0x00, 0x00,  // Additional: 0
                // Name query for * (CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA)
                0x20,        // Name length (32)
                0x43, 0x4B,  // CK (encoded *)
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x41, 0x41, 0x41, 0x41, 0x41, 0x41,
                0x00,        // Name terminator
                0x00, 0x21,  // Type: NBSTAT
                0x00, 0x01   // Class: IN
            };
            
            InetAddress broadcastAddress = InetAddress.getByName("255.255.255.255");
            DatagramPacket packet = new DatagramPacket(
                netbiosQuery, netbiosQuery.length, broadcastAddress, NETBIOS_PORT);
            socket.send(packet);
            
            Log.d(TAG, "NetBIOS query sent");
            
            // Listen for responses
            byte[] responseBuffer = new byte[1024];
            DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
            
            long endTime = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < endTime) {
                try {
                    socket.receive(responsePacket);
                    processNetBiosResponse(responsePacket);
                } catch (SocketTimeoutException e) {
                    // Continue
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "NetBIOS discovery error", e);
        } finally {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        }
        
        Log.d(TAG, "NetBIOS discovery completed");
    }
    
    private void processNetBiosResponse(DatagramPacket packet) {
        try {
            byte[] data = packet.getData();
            int length = packet.getLength();
            
            if (length < 57) return; // Minimum NetBIOS response size
            
            Map<String, String> deviceInfo = new HashMap<>();
            deviceInfo.put("ipAddress", packet.getAddress().getHostAddress());
            deviceInfo.put("discoveryMethod", "NetBIOS");
            deviceInfo.put("lastSeen", String.valueOf(System.currentTimeMillis()));
            
            // Parse NetBIOS response
            // Skip header (12 bytes) and question section (variable)
            int offset = 56; // Typical offset to start of name entries
            
            if (offset < length) {
                int numNames = data[offset] & 0xFF;
                offset++;
                
                for (int i = 0; i < numNames && offset + 18 <= length; i++) {
                    // NetBIOS name is 15 characters + 1 type byte
                    byte[] nameBytes = new byte[15];
                    System.arraycopy(data, offset, nameBytes, 0, 15);
                    String name = new String(nameBytes, StandardCharsets.US_ASCII).trim();
                    byte nameType = data[offset + 15];
                    
                    // Type 0x00 is workstation/computer name
                    if (nameType == 0x00 && !name.isEmpty()) {
                        deviceInfo.put("hostname", name);
                        deviceInfo.put("deviceName", name);
                    }
                    // Type 0x20 is file server
                    else if (nameType == 0x20) {
                        deviceInfo.put("deviceType", "server");
                    }
                    
                    offset += 18; // 16 name bytes + 2 flag bytes
                }
                
                // MAC address is at the end
                if (offset + 6 <= length) {
                    byte[] macBytes = new byte[6];
                    System.arraycopy(data, offset, macBytes, 0, 6);
                    deviceInfo.put("macAddress", formatMacAddress(macBytes));
                }
            }
            
            if (deviceInfo.containsKey("macAddress") || deviceInfo.containsKey("hostname")) {
                if (!deviceInfo.containsKey("macAddress")) {
                    deviceInfo.put("macAddress", generatePseudoMac(deviceInfo.get("ipAddress")));
                }
                addOrUpdateDevice(deviceInfo);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error processing NetBIOS response", e);
        }
    }
    
    // ============================================================================
    // Helper Methods
    // ============================================================================
    
    private void addOrUpdateDevice(Map<String, String> deviceInfo) {
        String macAddress = deviceInfo.get("macAddress");
        if (macAddress == null || macAddress.isEmpty()) {
            return;
        }
        
        // Normalize MAC address
        macAddress = macAddress.toUpperCase().replace("-", ":");
        deviceInfo.put("macAddress", macAddress);
        
        boolean isNew = !discoveredDevices.containsKey(macAddress);
        
        if (isNew) {
            discoveredDevices.put(macAddress, new HashMap<>(deviceInfo));
            notifyDeviceFound(deviceInfo);
        } else {
            // Merge with existing info
            Map<String, String> existing = discoveredDevices.get(macAddress);
            for (Map.Entry<String, String> entry : deviceInfo.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                // Don't overwrite non-empty values with empty ones
                if (value != null && !value.isEmpty()) {
                    existing.put(key, value);
                }
            }
            notifyDeviceUpdated(existing);
        }
    }
    
    private void resolveHostname(Map<String, String> deviceInfo) {
        String ipAddress = deviceInfo.get("ipAddress");
        if (ipAddress == null) return;
        
        try {
            InetAddress addr = InetAddress.getByName(ipAddress);
            String hostname = addr.getHostName();
            if (hostname != null && !hostname.equals(ipAddress)) {
                deviceInfo.put("hostname", hostname);
                if (!deviceInfo.containsKey("deviceName")) {
                    deviceInfo.put("deviceName", hostname);
                }
            }
        } catch (Exception e) {
            // Hostname resolution failed
        }
    }
    
    private void identifyDeviceFromMac(Map<String, String> deviceInfo) {
        String mac = deviceInfo.get("macAddress");
        if (mac == null || mac.length() < 8) return;
        
        // Get OUI (first 3 bytes)
        String oui = mac.substring(0, 8).toUpperCase();
        
        // Common vendor OUIs
        Map<String, String> vendors = new HashMap<>();
        vendors.put("00:1A:79", "Apple");
        vendors.put("3C:22:FB", "Apple");
        vendors.put("F0:18:98", "Apple");
        vendors.put("00:50:56", "VMware");
        vendors.put("00:0C:29", "VMware");
        vendors.put("00:15:5D", "Microsoft");
        vendors.put("B8:27:EB", "Raspberry Pi");
        vendors.put("DC:A6:32", "Raspberry Pi");
        vendors.put("E4:5F:01", "Raspberry Pi");
        vendors.put("00:1E:06", "ASUS");
        vendors.put("00:23:24", "Cisco");
        vendors.put("00:17:9A", "Dell");
        vendors.put("00:21:9B", "Dell");
        vendors.put("00:25:64", "Dell");
        vendors.put("18:A9:05", "Hewlett Packard");
        vendors.put("00:1F:29", "Hewlett Packard");
        vendors.put("00:1C:C4", "Hewlett Packard");
        vendors.put("00:1A:A0", "Lenovo");
        vendors.put("00:06:1B", "Linksys");
        vendors.put("00:18:39", "Linksys");
        vendors.put("00:1E:58", "D-Link");
        vendors.put("00:26:5A", "D-Link");
        vendors.put("00:24:B2", "NETGEAR");
        vendors.put("20:4E:7F", "NETGEAR");
        vendors.put("00:18:E7", "TP-LINK");
        vendors.put("50:C7:BF", "TP-LINK");
        vendors.put("00:0C:42", "MikroTik");
        vendors.put("64:D1:54", "MikroTik");
        vendors.put("00:1B:63", "Samsung");
        vendors.put("00:12:FB", "Samsung");
        vendors.put("00:1F:CC", "LG");
        vendors.put("00:1C:62", "LG");
        vendors.put("00:04:4B", "NVIDIA");
        vendors.put("00:1F:A7", "Sony");
        vendors.put("00:13:15", "Sony");
        
        String vendor = vendors.get(oui);
        if (vendor != null) {
            deviceInfo.put("manufacturer", vendor);
        }
    }
    
    private String formatMacAddress(byte[] bytes) {
        if (bytes == null || bytes.length != 6) return "";
        return String.format("%02X:%02X:%02X:%02X:%02X:%02X",
            bytes[0], bytes[1], bytes[2], bytes[3], bytes[4], bytes[5]);
    }
    
    private String generatePseudoMac(String ipAddress) {
        if (ipAddress == null) return "00:00:00:00:00:00";
        
        // Generate a pseudo MAC based on IP for devices that don't report MAC
        int hash = ipAddress.hashCode();
        return String.format("02:%02X:%02X:%02X:%02X:%02X",
            (hash >> 24) & 0xFF, (hash >> 16) & 0xFF, (hash >> 8) & 0xFF,
            hash & 0xFF, (hash >> 4) & 0xFF);
    }
    
    private String generateMacFromUuid(String uuid) {
        if (uuid == null || uuid.isEmpty()) return generatePseudoMac(uuid);
        
        int hash = uuid.hashCode();
        return String.format("02:%02X:%02X:%02X:%02X:%02X",
            (hash >> 24) & 0xFF, (hash >> 16) & 0xFF, (hash >> 8) & 0xFF,
            hash & 0xFF, (hash >> 4) & 0xFF);
    }
    
    private Map<String, String> parseJsonToMap(String json) {
        Map<String, String> map = new HashMap<>();
        
        try {
            // Simple JSON parsing without external library
            json = json.trim();
            if (!json.startsWith("{") || !json.endsWith("}")) {
                return map;
            }
            
            json = json.substring(1, json.length() - 1);
            
            // Split by commas (simple approach, doesn't handle nested objects)
            StringBuilder currentToken = new StringBuilder();
            boolean inQuotes = false;
            boolean inArray = false;
            
            for (int i = 0; i < json.length(); i++) {
                char c = json.charAt(i);
                
                if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                    inQuotes = !inQuotes;
                } else if (c == '[') {
                    inArray = true;
                } else if (c == ']') {
                    inArray = false;
                }
                
                if (c == ',' && !inQuotes && !inArray) {
                    parseKeyValue(currentToken.toString().trim(), map);
                    currentToken = new StringBuilder();
                } else {
                    currentToken.append(c);
                }
            }
            
            if (currentToken.length() > 0) {
                parseKeyValue(currentToken.toString().trim(), map);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error parsing JSON", e);
        }
        
        return map;
    }
    
    private void parseKeyValue(String token, Map<String, String> map) {
        int colonIndex = token.indexOf(':');
        if (colonIndex > 0) {
            String key = token.substring(0, colonIndex).trim();
            String value = token.substring(colonIndex + 1).trim();
            
            // Remove quotes
            if (key.startsWith("\"") && key.endsWith("\"")) {
                key = key.substring(1, key.length() - 1);
            }
            if (value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            
            map.put(key, value);
        }
    }
    
    // ============================================================================
    // Notification Methods
    // ============================================================================
    
    private void notifyDeviceFound(Map<String, String> deviceInfo) {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onDeviceFound(deviceInfo);
            }
        });
    }
    
    private void notifyDeviceUpdated(Map<String, String> deviceInfo) {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onDeviceUpdated(deviceInfo);
            }
        });
    }
    
    private void notifyDeviceLost(String macAddress) {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onDeviceLost(macAddress);
            }
        });
    }
    
    private void notifyDiscoveryStarted() {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onDiscoveryStarted();
            }
        });
    }
    
    private void notifyDiscoveryFinished() {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onDiscoveryFinished();
            }
        });
    }
    
    private void notifyError(String message) {
        mainHandler.post(() -> {
            for (DiscoveryListener listener : listeners) {
                listener.onError(message);
            }
        });
    }
    
    /**
     * Release all resources.
     */
    public void destroy() {
        stopDiscovery();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
        listeners.clear();
    }
}
