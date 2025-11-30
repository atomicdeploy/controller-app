package com.atomicdeploy.controller.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SearchView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.atomicdeploy.controller.R;
import com.atomicdeploy.controller.databinding.ActivityMainBinding;
import com.atomicdeploy.controller.network.NetworkDiscoveryManager;
import com.atomicdeploy.controller.utils.DeviceStorage;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Main activity for the Controller app.
 * Displays discovered and saved devices with a beautiful Material Design 3 interface.
 */
public class MainActivity extends AppCompatActivity implements 
        NetworkDiscoveryManager.DiscoveryListener,
        DeviceAdapter.DeviceClickListener {
    
    private ActivityMainBinding binding;
    private NetworkDiscoveryManager discoveryManager;
    private DeviceStorage deviceStorage;
    private DeviceAdapter adapter;
    
    private List<Map<String, String>> allDiscoveredDevices = new ArrayList<>();
    private List<Map<String, String>> savedDevices = new ArrayList<>();
    private List<Map<String, String>> displayedDevices = new ArrayList<>();
    
    private int currentTab = 0; // 0 = Saved, 1 = Discovered
    private String searchQuery = "";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        
        setupToolbar();
        setupTabs();
        setupRecyclerView();
        setupSwipeRefresh();
        setupFab();
        
        // Initialize managers
        discoveryManager = new NetworkDiscoveryManager(this);
        discoveryManager.addListener(this);
        deviceStorage = new DeviceStorage(this);
        
        // Load saved devices
        loadSavedDevices();
        
        // Start discovery automatically
        startDiscovery();
    }
    
    private void setupToolbar() {
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(R.string.app_name);
        }
    }
    
    private void setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                currentTab = tab.getPosition();
                updateDeviceList();
                updateEmptyState();
            }
            
            @Override
            public void onTabUnselected(TabLayout.Tab tab) {}
            
            @Override
            public void onTabReselected(TabLayout.Tab tab) {}
        });
    }
    
    private void setupRecyclerView() {
        adapter = new DeviceAdapter(this, displayedDevices, this, deviceStorage);
        binding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerView.setAdapter(adapter);
        binding.recyclerView.setHasFixedSize(false);
    }
    
    private void setupSwipeRefresh() {
        binding.swipeRefresh.setColorSchemeResources(
            R.color.primary,
            R.color.secondary,
            R.color.tertiary
        );
        binding.swipeRefresh.setOnRefreshListener(this::startDiscovery);
    }
    
    private void setupFab() {
        binding.fab.setOnClickListener(v -> startDiscovery());
    }
    
    private void loadSavedDevices() {
        savedDevices = deviceStorage.getSavedDevices();
        updateDeviceList();
        updateEmptyState();
    }
    
    private void startDiscovery() {
        binding.swipeRefresh.setRefreshing(true);
        discoveryManager.startDiscovery();
    }
    
    private void updateDeviceList() {
        displayedDevices.clear();
        
        List<Map<String, String>> sourceList;
        if (currentTab == 0) {
            sourceList = savedDevices;
        } else {
            sourceList = allDiscoveredDevices;
        }
        
        // Apply search filter
        if (searchQuery.isEmpty()) {
            displayedDevices.addAll(sourceList);
        } else {
            String query = searchQuery.toLowerCase();
            for (Map<String, String> device : sourceList) {
                String name = device.get("deviceName");
                String hostname = device.get("hostname");
                String ip = device.get("ipAddress");
                String mac = device.get("macAddress");
                
                if ((name != null && name.toLowerCase().contains(query)) ||
                    (hostname != null && hostname.toLowerCase().contains(query)) ||
                    (ip != null && ip.toLowerCase().contains(query)) ||
                    (mac != null && mac.toLowerCase().contains(query))) {
                    displayedDevices.add(device);
                }
            }
        }
        
        // Update online status for saved devices
        if (currentTab == 0) {
            for (Map<String, String> saved : displayedDevices) {
                String mac = saved.get("macAddress");
                boolean isOnline = false;
                for (Map<String, String> discovered : allDiscoveredDevices) {
                    if (mac != null && mac.equals(discovered.get("macAddress"))) {
                        isOnline = true;
                        // Update with latest info
                        saved.putAll(discovered);
                        break;
                    }
                }
                saved.put("isOnline", String.valueOf(isOnline));
            }
        }
        
        adapter.notifyDataSetChanged();
        updateTabBadges();
    }
    
    private void updateTabBadges() {
        TabLayout.Tab savedTab = binding.tabLayout.getTabAt(0);
        TabLayout.Tab discoveredTab = binding.tabLayout.getTabAt(1);
        
        if (savedTab != null) {
            if (savedDevices.isEmpty()) {
                savedTab.removeBadge();
            } else {
                savedTab.getOrCreateBadge().setNumber(savedDevices.size());
            }
        }
        
        if (discoveredTab != null) {
            if (allDiscoveredDevices.isEmpty()) {
                discoveredTab.removeBadge();
            } else {
                discoveredTab.getOrCreateBadge().setNumber(allDiscoveredDevices.size());
            }
        }
    }
    
    private void updateEmptyState() {
        boolean isEmpty = displayedDevices.isEmpty();
        binding.emptyState.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        binding.recyclerView.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
        
        if (isEmpty) {
            if (!searchQuery.isEmpty()) {
                binding.emptyTitle.setText(R.string.empty_search_title);
                binding.emptySubtitle.setText(R.string.empty_search_subtitle);
                binding.emptyIcon.setImageResource(R.drawable.ic_search);
            } else if (currentTab == 0) {
                binding.emptyTitle.setText(R.string.empty_saved_title);
                binding.emptySubtitle.setText(R.string.empty_saved_subtitle);
                binding.emptyIcon.setImageResource(R.drawable.ic_bookmark_border);
            } else {
                binding.emptyTitle.setText(R.string.empty_discovered_title);
                binding.emptySubtitle.setText(R.string.empty_discovered_subtitle);
                binding.emptyIcon.setImageResource(R.drawable.ic_wifi_find);
            }
        }
    }
    
    // ========================================================================
    // Menu
    // ========================================================================
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        
        MenuItem searchItem = menu.findItem(R.id.action_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.hint_search_devices));
        
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                return false;
            }
            
            @Override
            public boolean onQueryTextChange(String newText) {
                searchQuery = newText;
                updateDeviceList();
                updateEmptyState();
                return true;
            }
        });
        
        searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override
            public boolean onMenuItemActionExpand(@NonNull MenuItem item) {
                return true;
            }
            
            @Override
            public boolean onMenuItemActionCollapse(@NonNull MenuItem item) {
                searchQuery = "";
                updateDeviceList();
                updateEmptyState();
                return true;
            }
        });
        
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        
        if (id == R.id.action_refresh) {
            startDiscovery();
            return true;
        } else if (id == R.id.action_settings) {
            // Open settings
            Toast.makeText(this, "Settings coming soon", Toast.LENGTH_SHORT).show();
            return true;
        }
        
        return super.onOptionsItemSelected(item);
    }
    
    // ========================================================================
    // Discovery Listener
    // ========================================================================
    
    @Override
    public void onDeviceFound(Map<String, String> deviceInfo) {
        runOnUiThread(() -> {
            // Check if device already exists
            String mac = deviceInfo.get("macAddress");
            boolean found = false;
            for (int i = 0; i < allDiscoveredDevices.size(); i++) {
                if (mac != null && mac.equals(allDiscoveredDevices.get(i).get("macAddress"))) {
                    allDiscoveredDevices.set(i, new HashMap<>(deviceInfo));
                    found = true;
                    break;
                }
            }
            if (!found) {
                allDiscoveredDevices.add(new HashMap<>(deviceInfo));
            }
            updateDeviceList();
            updateEmptyState();
        });
    }
    
    @Override
    public void onDeviceUpdated(Map<String, String> deviceInfo) {
        onDeviceFound(deviceInfo);
    }
    
    @Override
    public void onDeviceLost(String macAddress) {
        runOnUiThread(() -> {
            allDiscoveredDevices.removeIf(d -> macAddress.equals(d.get("macAddress")));
            updateDeviceList();
            updateEmptyState();
        });
    }
    
    @Override
    public void onDiscoveryStarted() {
        runOnUiThread(() -> {
            binding.swipeRefresh.setRefreshing(true);
        });
    }
    
    @Override
    public void onDiscoveryFinished() {
        runOnUiThread(() -> {
            binding.swipeRefresh.setRefreshing(false);
            Snackbar.make(binding.getRoot(), 
                getString(R.string.msg_scan_complete) + " (" + allDiscoveredDevices.size() + " devices)",
                Snackbar.LENGTH_SHORT).show();
        });
    }
    
    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show();
        });
    }
    
    // ========================================================================
    // Device Click Listener
    // ========================================================================
    
    @Override
    public void onDeviceClick(Map<String, String> device) {
        showDeviceDetailsDialog(device);
    }
    
    @Override
    public void onSaveClick(Map<String, String> device) {
        String mac = device.get("macAddress");
        if (deviceStorage.isDeviceSaved(mac)) {
            deviceStorage.removeDevice(mac);
            Snackbar.make(binding.getRoot(), R.string.msg_device_removed, Snackbar.LENGTH_SHORT).show();
        } else {
            deviceStorage.saveDevice(device);
            Snackbar.make(binding.getRoot(), R.string.msg_device_saved, Snackbar.LENGTH_SHORT).show();
        }
        loadSavedDevices();
    }
    
    @Override
    public void onDeleteClick(Map<String, String> device) {
        new MaterialAlertDialogBuilder(this, R.style.Theme_Controller_Dialog)
            .setTitle(R.string.dialog_delete_title)
            .setMessage(R.string.dialog_delete_message)
            .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                deviceStorage.removeDevice(device.get("macAddress"));
                loadSavedDevices();
                Snackbar.make(binding.getRoot(), R.string.msg_device_deleted, Snackbar.LENGTH_SHORT).show();
            })
            .setNegativeButton(R.string.action_cancel, null)
            .show();
    }
    
    @Override
    public void onConnectClick(Map<String, String> device) {
        String ip = device.get("ipAddress");
        if (ip != null) {
            // Open browser or try to connect
            String url = "http://" + ip;
            String port = device.get("port");
            if (port != null && !port.isEmpty() && !port.equals("80")) {
                url += ":" + port;
            }
            
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
            try {
                startActivity(browserIntent);
            } catch (Exception e) {
                Snackbar.make(binding.getRoot(), R.string.msg_connection_failed, Snackbar.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    public void onCopyIpClick(Map<String, String> device) {
        String ip = device.get("ipAddress");
        if (ip != null) {
            DeviceStorage.copyToClipboard(this, "IP Address", ip);
        }
    }
    
    @Override
    public void onCopyMacClick(Map<String, String> device) {
        String mac = device.get("macAddress");
        if (mac != null) {
            DeviceStorage.copyToClipboard(this, "MAC Address", mac);
        }
    }
    
    private void showDeviceDetailsDialog(Map<String, String> device) {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_device_details, null);
        
        // Populate dialog views
        android.widget.TextView nameView = dialogView.findViewById(R.id.deviceName);
        android.widget.TextView ipView = dialogView.findViewById(R.id.ipAddress);
        android.widget.TextView macView = dialogView.findViewById(R.id.macAddress);
        android.widget.TextView hostnameView = dialogView.findViewById(R.id.hostname);
        android.widget.TextView manufacturerView = dialogView.findViewById(R.id.manufacturer);
        android.widget.TextView firmwareView = dialogView.findViewById(R.id.firmwareVersion);
        android.widget.TextView lastSeenView = dialogView.findViewById(R.id.lastSeen);
        android.widget.TextView discoveryMethodView = dialogView.findViewById(R.id.discoveryMethod);
        android.widget.ImageView iconView = dialogView.findViewById(R.id.deviceIcon);
        
        String name = device.get("deviceName");
        if (name == null || name.isEmpty()) name = device.get("hostname");
        if (name == null || name.isEmpty()) name = "Unknown Device";
        
        nameView.setText(name);
        ipView.setText(device.getOrDefault("ipAddress", "—"));
        macView.setText(device.getOrDefault("macAddress", "—"));
        hostnameView.setText(device.getOrDefault("hostname", "—"));
        manufacturerView.setText(device.getOrDefault("manufacturer", "—"));
        firmwareView.setText(device.getOrDefault("firmwareVersion", "—"));
        discoveryMethodView.setText(device.getOrDefault("discoveryMethod", "—"));
        
        String lastSeenStr = device.get("lastSeen");
        if (lastSeenStr != null && !lastSeenStr.isEmpty()) {
            try {
                long lastSeen = Long.parseLong(lastSeenStr);
                lastSeenView.setText(DeviceStorage.formatTimeAgo(lastSeen));
            } catch (NumberFormatException e) {
                lastSeenView.setText("—");
            }
        } else {
            lastSeenView.setText("—");
        }
        
        iconView.setImageResource(DeviceStorage.getDeviceIcon(device));
        
        new MaterialAlertDialogBuilder(this, R.style.Theme_Controller_Dialog)
            .setView(dialogView)
            .setPositiveButton(R.string.action_done, null)
            .setNeutralButton(R.string.action_connect, (dialog, which) -> onConnectClick(device))
            .show();
    }
    
    // ========================================================================
    // Lifecycle
    // ========================================================================
    
    @Override
    protected void onResume() {
        super.onResume();
        loadSavedDevices();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (discoveryManager != null) {
            discoveryManager.removeListener(this);
            discoveryManager.destroy();
        }
    }
}
