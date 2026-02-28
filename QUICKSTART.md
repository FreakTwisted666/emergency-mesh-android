# Emergency Mesh - Quick Start Guide

## ⚠️ EMERGENCY USE - DEPLOY NOW

### Build APK (5 minutes)

```bash
cd emergency-mesh/android

# Make gradlew executable
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug

# APK location:
# app/build/outputs/apk/debug/app-debug.apk
```

### Install on Your Phone

1. **Transfer APK** to your phone (USB, Bluetooth, file share)
2. **Enable unknown sources**: Settings → Security → Unknown Sources
3. **Install** the APK file
4. **Grant permissions** when prompted (WiFi, Location, Bluetooth)

### Test It Works

1. Open app on 2+ phones
2. Grant all permissions
3. You should see "Mesh Active" status
4. Tap SOS button - should broadcast distress signal
5. Other phones should receive SOS alert

### Share with Friends/Family

1. Send them the APK file
2. They install same way
3. No internet needed for mesh to work
4. Test range: 30-100m between devices

## Emergency Features

### SOS Beacon
- Tap big red SOS button
- Broadcasts every 30 seconds
- Includes GPS location
- Visible to all nearby devices
- Tap again to cancel

### Share Location
- Tap "Share Location" button
- Sends GPS coordinates to mesh
- Updates every 60 seconds

### Medical ID
- Tap "Medical ID" button
- Enter: blood type, allergies, medications
- Visible to rescuers

## Battery Tips

- App uses <5% battery/hour normally
- Enable "Battery Saver" mode if needed
- Reduces broadcast frequency
- Keeps mesh running in background

## Important Notes

1. **Keep app open** or running in background
2. **Don't force close** - mesh stops working
3. **WiFi must be ON** - mesh uses WiFi Direct
4. **Location ON** - needed for WiFi scanning + GPS
5. **Test BEFORE emergency** - don't wait for disaster

## Distribution Without App Store

### Option 1: Direct APK Share
- Copy APK to USB drive
- Share via Bluetooth
- Upload to local server
- Email to contacts

### Option 2: AltStore (iOS - future)
- Build IPA for iOS
- Install AltStore on friends' phones
- Distribute via AltStore
- Refresh every 7 days

## Troubleshooting

**"Mesh not connecting"**
- Check WiFi is ON
- Grant location permission
- Restart app

**"Can't find devices"**
- Move closer (within 30m)
- Check both devices have app open
- Try scanning QR code

**"SOS not working"**
- Check location permission granted
- Wait 30 seconds for first broadcast
- Check notification appears

## Next Steps

1. ✅ Build and test on your phone
2. ✅ Share with 2-3 friends
3. ✅ Test multi-hop (3 phones in chain)
4. ✅ Test SOS broadcast
5. ✅ Document any issues
6. ✅ Share APK with more people

---

**This could save lives. Build it. Test it. Share it.**
