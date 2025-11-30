package com.atomicdeploy.controller.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Utility class for device storage using SharedPreferences.
 * Stores saved devices as JSON in SharedPreferences.
 */
public class DeviceStorage {
    
    private static final String PREFS_NAME = "controller_prefs";
    private static final String KEY_SAVED_DEVICES = "saved_devices";
    
    private final SharedPreferences prefs;
    
    public DeviceStorage(Context context) {
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    /**
     * Save a device to persistent storage.
     */
    public void saveDevice(Map<String, String> device) {
        String macAddress = device.get("macAddress");
        if (macAddress == null || macAddress.isEmpty()) {
            return;
        }
        
        List<Map<String, String>> savedDevices = getSavedDevices();
        
        // Check if device already exists
        boolean found = false;
        for (int i = 0; i < savedDevices.size(); i++) {
            Map<String, String> existing = savedDevices.get(i);
            if (macAddress.equals(existing.get("macAddress"))) {
                // Update existing device
                savedDevices.set(i, new HashMap<>(device));
                found = true;
                break;
            }
        }
        
        if (!found) {
            savedDevices.add(new HashMap<>(device));
        }
        
        persistDevices(savedDevices);
    }
    
    /**
     * Remove a device from persistent storage.
     */
    public void removeDevice(String macAddress) {
        if (macAddress == null) return;
        
        List<Map<String, String>> savedDevices = getSavedDevices();
        savedDevices.removeIf(device -> macAddress.equals(device.get("macAddress")));
        persistDevices(savedDevices);
    }
    
    /**
     * Check if a device is saved.
     */
    public boolean isDeviceSaved(String macAddress) {
        if (macAddress == null) return false;
        
        List<Map<String, String>> savedDevices = getSavedDevices();
        for (Map<String, String> device : savedDevices) {
            if (macAddress.equals(device.get("macAddress"))) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Get all saved devices.
     */
    public List<Map<String, String>> getSavedDevices() {
        List<Map<String, String>> devices = new ArrayList<>();
        String json = prefs.getString(KEY_SAVED_DEVICES, "[]");
        
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                Map<String, String> device = new HashMap<>();
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    device.put(key, obj.optString(key, ""));
                }
                devices.add(device);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return devices;
    }
    
    /**
     * Update a saved device's online status.
     */
    public void updateDeviceStatus(String macAddress, boolean isOnline) {
        List<Map<String, String>> savedDevices = getSavedDevices();
        
        for (Map<String, String> device : savedDevices) {
            if (macAddress.equals(device.get("macAddress"))) {
                device.put("isOnline", String.valueOf(isOnline));
                device.put("lastSeen", String.valueOf(System.currentTimeMillis()));
                break;
            }
        }
        
        persistDevices(savedDevices);
    }
    
    private void persistDevices(List<Map<String, String>> devices) {
        try {
            JSONArray array = new JSONArray();
            for (Map<String, String> device : devices) {
                JSONObject obj = new JSONObject();
                for (Map.Entry<String, String> entry : device.entrySet()) {
                    obj.put(entry.getKey(), entry.getValue());
                }
                array.put(obj);
            }
            prefs.edit().putString(KEY_SAVED_DEVICES, array.toString()).apply();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }
    
    /**
     * Copy text to clipboard.
     */
    public static void copyToClipboard(Context context, String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText(label, text);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(context, label + " copied", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * Format time ago string.
     */
    public static String formatTimeAgo(long timestamp) {
        if (timestamp <= 0) return "Never";
        
        long now = System.currentTimeMillis();
        long diff = now - timestamp;
        
        if (diff < 60000) { // Less than 1 minute
            return "Just now";
        } else if (diff < 3600000) { // Less than 1 hour
            int minutes = (int) (diff / 60000);
            return minutes + " min ago";
        } else if (diff < 86400000) { // Less than 1 day
            int hours = (int) (diff / 3600000);
            return hours + " hr ago";
        } else {
            int days = (int) (diff / 86400000);
            return days + " days ago";
        }
    }
    
    /**
     * Get device type icon resource ID based on device type or hostname.
     */
    public static int getDeviceIcon(Map<String, String> device) {
        String deviceType = device.get("deviceType");
        String hostname = device.get("hostname");
        String deviceName = device.get("deviceName");
        String manufacturer = device.get("manufacturer");
        
        if (deviceType == null) deviceType = "";
        if (hostname == null) hostname = "";
        if (deviceName == null) deviceName = "";
        if (manufacturer == null) manufacturer = "";
        
        String combined = (deviceType + " " + hostname + " " + deviceName + " " + manufacturer).toLowerCase();
        
        // Check for routers
        if (combined.contains("router") || combined.contains("gateway") ||
            combined.contains("mikrotik") || combined.contains("netgear") ||
            combined.contains("tp-link") || combined.contains("linksys") ||
            combined.contains("ubiquiti") || combined.contains("asus")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_router;
        }
        
        // Check for phones
        if (combined.contains("iphone") || combined.contains("android") ||
            combined.contains("phone") || combined.contains("pixel") ||
            combined.contains("galaxy") || combined.contains("oneplus")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_phone;
        }
        
        // Check for tablets
        if (combined.contains("ipad") || combined.contains("tablet")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_tablet;
        }
        
        // Check for laptops
        if (combined.contains("macbook") || combined.contains("laptop") ||
            combined.contains("thinkpad") || combined.contains("notebook")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_laptop;
        }
        
        // Check for desktops
        if (combined.contains("imac") || combined.contains("desktop") ||
            combined.contains("pc") || combined.contains("workstation")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_desktop;
        }
        
        // Check for servers
        if (combined.contains("server") || combined.contains("nas") ||
            combined.contains("synology") || combined.contains("qnap")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_server;
        }
        
        // Check for TVs
        if (combined.contains("tv") || combined.contains("roku") ||
            combined.contains("firetv") || combined.contains("chromecast") ||
            combined.contains("samsung") || combined.contains("lg") ||
            combined.contains("sony") || combined.contains("vizio")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_tv;
        }
        
        // Check for speakers
        if (combined.contains("echo") || combined.contains("alexa") ||
            combined.contains("homepod") || combined.contains("sonos") ||
            combined.contains("speaker") || combined.contains("google-home")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_speaker;
        }
        
        // Check for smart home
        if (combined.contains("hue") || combined.contains("nest") ||
            combined.contains("ring") || combined.contains("smart")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_smart_home;
        }
        
        // Check for printers
        if (combined.contains("printer") || combined.contains("epson") ||
            combined.contains("canon") || combined.contains("brother")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_printer;
        }
        
        // Check for cameras
        if (combined.contains("camera") || combined.contains("cam") ||
            combined.contains("wyze") || combined.contains("arlo")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_camera;
        }
        
        // Check for gaming
        if (combined.contains("playstation") || combined.contains("xbox") ||
            combined.contains("nintendo") || combined.contains("switch") ||
            combined.contains("game")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_gamepad;
        }
        
        // Check for media
        if (combined.contains("plex") || combined.contains("kodi") ||
            combined.contains("appletv") || combined.contains("media")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_media;
        }
        
        // Check for storage
        if (combined.contains("storage") || combined.contains("backup") ||
            combined.contains("drive")) {
            return com.atomicdeploy.controller.R.drawable.ic_device_storage;
        }
        
        // Default unknown
        return com.atomicdeploy.controller.R.drawable.ic_device_unknown;
    }
}
