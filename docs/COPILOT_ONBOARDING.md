# GitHub Copilot Onboarding Guide

Welcome to the Controller App project! This guide will help you get started with developing and contributing to this Android application using GitHub Copilot.

## Project Overview

Controller is a modern Android application designed to discover and manage network devices. It uses multiple discovery protocols to find devices on your local network and provides a beautiful Material Design 3 interface for managing them.

## Architecture

```
app/
├── src/main/
│   ├── java/com/atomicdeploy/controller/
│   │   ├── ControllerApplication.java    # Application class
│   │   ├── network/
│   │   │   └── NetworkDiscoveryManager.java  # Multi-protocol discovery engine
│   │   ├── ui/
│   │   │   ├── MainActivity.java         # Main screen with device list
│   │   │   └── DeviceAdapter.java        # RecyclerView adapter for devices
│   │   └── utils/
│   │       └── DeviceStorage.java        # SharedPreferences-based storage
│   └── res/
│       ├── layout/                        # XML layouts
│       ├── values/                        # Strings, colors, themes, dimensions
│       ├── values-night/                  # Dark mode resources
│       ├── drawable/                      # Icons and shapes
│       └── menu/                          # Menu definitions
└── build.gradle                           # App-level build config
```

## Key Components

### NetworkDiscoveryManager

The heart of the application. It implements device discovery using:

1. **ARP** - Reads `/proc/net/arp` to find devices
2. **mDNS** - Multicast DNS for Bonjour/Zeroconf services
3. **NSD (DNS-SD)** - Android's Network Service Discovery API
4. **UDP Broadcast** - Custom discovery protocol on port 9999
5. **SSDP/UPnP** - Simple Service Discovery Protocol
6. **MNDP** - MikroTik Neighbor Discovery Protocol
7. **NetBIOS** - Windows/Samba device discovery

### Device Storage

Uses SharedPreferences to persist saved devices as JSON. Simple and lightweight approach without Room database complexity.

### UI Components

- **MainActivity**: Tabbed interface (Saved/Discovered) with SwipeRefresh
- **DeviceAdapter**: Beautiful card-based device list with status indicators
- Material Design 3 theming with vibrant colors
- Full dark mode support
- RTL language support

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Android SDK 34

### Building

```bash
# Debug build
./gradlew assembleDebug

# Release build  
./gradlew assembleRelease
```

### Running

1. Open the project in Android Studio
2. Connect an Android device or start an emulator
3. Click Run or use `Shift+F10`

## Development Tips for Copilot

### Adding a New Discovery Protocol

1. Add a new method in `NetworkDiscoveryManager.java`:
```java
private void discoverViaNewProtocol() {
    // Implement discovery logic
    // Call addOrUpdateDevice(deviceInfo) when a device is found
}
```

2. Call it from `startDiscovery()`:
```java
executorService.submit(this::discoverViaNewProtocol);
```

### Adding New Device Types

1. Add icon to `res/drawable/ic_device_*.xml`
2. Update `DeviceStorage.getDeviceIcon()` to detect the new type

### Theming

- Colors are in `res/values/colors.xml` and `res/values-night/colors.xml`
- Styles are in `res/values/themes.xml`
- Use `@color/` and `?attr/` references for theme-aware colors

### UI Components

- Use `MaterialCardView` for cards
- Use `Chip` for status badges
- Follow Material Design 3 spacing: 4dp, 8dp, 12dp, 16dp, 24dp, 32dp

## Copilot Suggestions

When working with this codebase, Copilot can help with:

1. **Network code**: Ask for help with socket programming, parsing protocols
2. **UI layouts**: Describe the UI you want in XML comments
3. **Device detection**: Add patterns to identify device types
4. **Error handling**: Improve robustness of network operations

### Example Prompts

- "Add support for discovering Philips Hue bridges"
- "Implement ping functionality to check device status"
- "Add a bottom sheet dialog for device actions"
- "Create a settings screen with theme selection"

## Testing

Currently, the project focuses on manual testing. Future enhancements could include:

- Unit tests for `DeviceStorage`
- Integration tests for discovery protocols
- UI tests using Espresso

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Test on a real device (emulator may not support all network features)
5. Submit a pull request

## Resources

- [Android Developer Documentation](https://developer.android.com)
- [Material Design 3](https://m3.material.io)
- [Network Service Discovery](https://developer.android.com/training/connect-devices-wirelessly/nsd)

## License

This project is open source. See the LICENSE file for details.
