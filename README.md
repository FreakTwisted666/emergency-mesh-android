# Emergency Mesh - Offline Communication for Emergencies

An offline-first mesh communication app for disaster/emergency scenarios where cellular networks and internet are unavailable.

## Features

- ✅ **Offline mesh messaging** - Text messages between nearby devices without internet
- ✅ **Multi-hop relay** - Messages hop through multiple devices to reach distant recipients
- ✅ **SOS beacon** - Emergency distress signal with GPS location
- ✅ **Location sharing** - Share GPS coordinates with rescue teams/family
- ✅ **Battery efficient** - Optimized for low power consumption
- ✅ **No account required** - Works immediately, no phone number/email needed
- ✅ **No internet dependency** - 100% offline operation
- ✅ **Encrypted messaging** - End-to-end encryption for privacy

## Technical Stack

- **Platform**: Android 8.0+ (API 26+)
- **Mesh Library**: Meshrabiya (WiFi Direct + Local Hotspots)
- **UI**: Jetpack Compose
- **Database**: Room
- **Encryption**: Google Tink (AES-256)

## Build Instructions

### Prerequisites
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

### Build APK

```bash
cd android

# Build debug APK
./gradlew assembleDebug

# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Install on Device

```bash
# Via ADB
adb install app/build/outputs/apk/debug/app-debug.apk

# Or share APK directly with users for sideloading
```

## Usage

### First Launch
1. Grant required permissions (WiFi, Location, Bluetooth)
2. App automatically starts mesh network service
3. Status indicator shows "Mesh Active" when ready

### Send SOS
1. Tap the large red SOS button
2. SOS beacon broadcasts every 30 seconds
3. Includes your GPS location (if available)
4. Tap again to cancel

### Connect to Other Devices
1. Tap "Scan QR" to scan another device's QR code
2. Or tap "My QR" to show your connect QR code
3. Devices connect automatically via WiFi Direct

### View Nearby Devices
- Bottom navigation → Devices tab
- Shows device count and signal strength
- Messages hop through devices automatically

## Permissions Required

- **WiFi**: Create mesh network (WiFi Direct/Hotspot)
- **Location**: GPS for SOS, WiFi scanning
- **Bluetooth**: Cross-platform communication (iOS bridge)
- **Camera**: QR code scanning
- **Foreground Service**: Keep mesh running in background

## Architecture

```
app/
├── ui/           # Jetpack Compose UI
├── mesh/         # Meshrabiya integration, MeshManager
├── sos/          # SOS beacon services
├── data/         # Room database, entities
└── ble/          # Bluetooth LE bridge (iOS)
```

## Distribution

### Sideloading (No Play Store)
1. Build APK: `./gradlew assembleDebug`
2. Share APK file directly
3. Users install via file manager
4. May need to enable "Install from unknown sources"

### AltStore-style (Future iOS)
- Build IPA for iOS
- Distribute via AltStore
- Refresh every 7 days

## Testing

### Minimum Setup
- 3+ Android devices
- Enable WiFi on all devices
- Install app on each device
- Test multi-hop: Device A → B → C

### Test Scenarios
1. **Direct messaging**: 2 devices in range
2. **Multi-hop**: 3+ devices in chain
3. **SOS broadcast**: Verify all devices receive SOS
4. **Battery drain**: Monitor battery usage over 1 hour
5. **Range test**: Measure max distance per hop

## Known Limitations

- **iOS**: Not yet supported (planned via Multipeer Connectivity)
- **Range**: ~30-100m per hop (WiFi range)
- **Hop limit**: Configurable (default: 5 hops)
- **Capacity**: Tested up to 50 devices

## Emergency Use Cases

- Earthquake (cell towers down)
- Hurricane/Flood (infrastructure damaged)
- Wildfire evacuation (family separation)
- Power outage (towers fail after 4-6 hours)
- Hiking/Camping (no coverage areas)
- Concerts/Festivals (networks overloaded)

## License

MIT License - Free for emergency/disaster relief use

## Contributing

This is an open-source emergency tool. Contributions welcome:
- Bug fixes
- Battery optimizations
- iOS port
- Additional emergency features

## Support

For issues/questions:
- GitHub Issues
- Matrix: #emergency-mesh:matrix.org

---

**Remember**: This app is for EMERGENCY USE. Test before you need it.
