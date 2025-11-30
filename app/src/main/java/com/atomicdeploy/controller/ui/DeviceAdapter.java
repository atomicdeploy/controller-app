package com.atomicdeploy.controller.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.atomicdeploy.controller.R;
import com.atomicdeploy.controller.utils.DeviceStorage;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;

import java.util.List;
import java.util.Map;

/**
 * RecyclerView adapter for displaying devices with a beautiful card-based layout.
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {
    
    private final Context context;
    private final List<Map<String, String>> devices;
    private final DeviceClickListener listener;
    private final DeviceStorage deviceStorage;
    
    public interface DeviceClickListener {
        void onDeviceClick(Map<String, String> device);
        void onSaveClick(Map<String, String> device);
        void onDeleteClick(Map<String, String> device);
        void onConnectClick(Map<String, String> device);
        void onCopyIpClick(Map<String, String> device);
        void onCopyMacClick(Map<String, String> device);
    }
    
    public DeviceAdapter(Context context, List<Map<String, String>> devices, 
                         DeviceClickListener listener, DeviceStorage deviceStorage) {
        this.context = context;
        this.devices = devices;
        this.listener = listener;
        this.deviceStorage = deviceStorage;
    }
    
    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        Map<String, String> device = devices.get(position);
        holder.bind(device);
    }
    
    @Override
    public int getItemCount() {
        return devices.size();
    }
    
    class DeviceViewHolder extends RecyclerView.ViewHolder {
        private final MaterialCardView card;
        private final ImageView deviceIcon;
        private final View statusIndicator;
        private final TextView deviceName;
        private final TextView deviceSubtitle;
        private final Chip statusChip;
        private final Chip firmwareChip;
        private final Chip savedChip;
        private final ImageButton menuButton;
        
        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            card = itemView.findViewById(R.id.card);
            deviceIcon = itemView.findViewById(R.id.deviceIcon);
            statusIndicator = itemView.findViewById(R.id.statusIndicator);
            deviceName = itemView.findViewById(R.id.deviceName);
            deviceSubtitle = itemView.findViewById(R.id.deviceSubtitle);
            statusChip = itemView.findViewById(R.id.statusChip);
            firmwareChip = itemView.findViewById(R.id.firmwareChip);
            savedChip = itemView.findViewById(R.id.savedChip);
            menuButton = itemView.findViewById(R.id.menuButton);
        }
        
        void bind(Map<String, String> device) {
            // Set device name
            String name = device.get("deviceName");
            if (name == null || name.isEmpty()) {
                name = device.get("hostname");
            }
            if (name == null || name.isEmpty()) {
                name = device.get("macAddress");
            }
            if (name == null || name.isEmpty()) {
                name = "Unknown Device";
            }
            deviceName.setText(name);
            
            // Set subtitle (IP + Manufacturer)
            StringBuilder subtitle = new StringBuilder();
            String ip = device.get("ipAddress");
            String manufacturer = device.get("manufacturer");
            
            if (ip != null && !ip.isEmpty()) {
                subtitle.append(ip);
            }
            if (manufacturer != null && !manufacturer.isEmpty()) {
                if (subtitle.length() > 0) subtitle.append(" • ");
                subtitle.append(manufacturer);
            }
            if (subtitle.length() == 0) {
                String mac = device.get("macAddress");
                if (mac != null) subtitle.append(mac);
            }
            deviceSubtitle.setText(subtitle.toString());
            
            // Set device icon
            int iconRes = DeviceStorage.getDeviceIcon(device);
            deviceIcon.setImageResource(iconRes);
            
            // Set online/offline status
            boolean isOnline = "true".equals(device.get("isOnline"));
            // For discovered devices that don't have explicit status, assume online
            if (!device.containsKey("isOnline") && device.containsKey("ipAddress")) {
                isOnline = true;
            }
            
            if (isOnline) {
                statusIndicator.setBackgroundResource(R.drawable.bg_status_online);
                statusChip.setText(R.string.status_online);
                statusChip.setChipBackgroundColorResource(R.color.chip_saved_bg);
                statusChip.setTextColor(ContextCompat.getColor(context, R.color.chip_saved_text));
                statusChip.setChipIconResource(R.drawable.ic_check_circle);
                statusChip.setChipIconTintResource(R.color.chip_saved_text);
            } else {
                statusIndicator.setBackgroundResource(R.drawable.bg_status_offline);
                statusChip.setText(R.string.status_offline);
                statusChip.setChipBackgroundColorResource(R.color.status_offline_light);
                statusChip.setTextColor(ContextCompat.getColor(context, R.color.status_offline));
                statusChip.setChipIconResource(R.drawable.ic_cancel);
                statusChip.setChipIconTintResource(R.color.status_offline);
            }
            
            // Set firmware chip
            String firmware = device.get("firmwareVersion");
            if (firmware != null && !firmware.isEmpty()) {
                firmwareChip.setVisibility(View.VISIBLE);
                firmwareChip.setText("v" + firmware);
            } else {
                firmwareChip.setVisibility(View.GONE);
            }
            
            // Set saved chip
            boolean isSaved = deviceStorage.isDeviceSaved(device.get("macAddress"));
            savedChip.setVisibility(isSaved ? View.VISIBLE : View.GONE);
            
            // Card click
            card.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDeviceClick(device);
                }
            });
            
            // Menu button click
            menuButton.setOnClickListener(v -> showPopupMenu(v, device, isSaved));
        }
        
        private void showPopupMenu(View anchor, Map<String, String> device, boolean isSaved) {
            PopupMenu popup = new PopupMenu(context, anchor);
            popup.getMenuInflater().inflate(R.menu.menu_device_context, popup.getMenu());
            
            // Update save/unsave menu item
            popup.getMenu().findItem(R.id.action_save).setVisible(!isSaved);
            popup.getMenu().findItem(R.id.action_unsave).setVisible(isSaved);
            
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                
                if (id == R.id.action_connect) {
                    if (listener != null) listener.onConnectClick(device);
                    return true;
                } else if (id == R.id.action_save) {
                    if (listener != null) listener.onSaveClick(device);
                    return true;
                } else if (id == R.id.action_unsave) {
                    if (listener != null) listener.onSaveClick(device);
                    return true;
                } else if (id == R.id.action_copy_ip) {
                    if (listener != null) listener.onCopyIpClick(device);
                    return true;
                } else if (id == R.id.action_copy_mac) {
                    if (listener != null) listener.onCopyMacClick(device);
                    return true;
                } else if (id == R.id.action_delete) {
                    if (listener != null) listener.onDeleteClick(device);
                    return true;
                } else if (id == R.id.action_details) {
                    if (listener != null) listener.onDeviceClick(device);
                    return true;
                }
                
                return false;
            });
            
            popup.show();
        }
    }
}
