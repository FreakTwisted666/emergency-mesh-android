# iOS Bridge - Emergency Mesh Communication

## Overview

This document provides the iOS implementation for bridging with Android Emergency Mesh devices using Multipeer Connectivity framework.

## Architecture

```
Android (Meshrabiya) <--WiFi Direct/Mesh--> Android (Meshrabiya)
       ^
       |
       | Bluetooth LE / WiFi
       |
       v
    iOS (Multipeer Connectivity)
```

## iOS Implementation

### 1. Project Setup

```swift
// EmergencyMeshApp.swift
import SwiftUI

@main
struct EmergencyMeshApp: App {
    @StateObject private var meshManager = MeshManager()
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(meshManager)
        }
    }
}
```

### 2. Mesh Manager

```swift
// MeshManager.swift
import Foundation
import MultipeerConnectivity
import CoreLocation
import Combine

class MeshManager: NSObject, ObservableObject {
    @Published var isConnected = false
    @Published var connectedPeers: [MCPeerID] = []
    @Published var receivedMessages: [Message] = []
    
    private var session: MCSession!
    private var advertiser: MCNearbyServiceAdvertiser!
    private var browser: MCNearbyServiceBrowser!
    private let peerId: MCPeerID
    private let serviceType = "emergencymesh"
    
    override init() {
        peerId = MCPeerID(displayName: UIDevice.current.name)
        super.init()
        
        session = MCSession(peer: peerId, securityIdentity: nil, encryptionPreference: .required)
        session.delegate = self
        
        advertiser = MCNearbyServiceAdvertiser(
            peer: peerId,
            discoveryInfo: nil,
            serviceType: serviceType
        )
        advertiser.delegate = self
        
        browser = MCNearbyServiceBrowser(peer: peerId, serviceType: serviceType)
        browser.delegate = self
        
        startAdvertising()
        startBrowsing()
    }
    
    func startAdvertising() {
        advertiser.startAdvertisingPeer()
    }
    
    func startBrowsing() {
        browser.startBrowsingForPeers()
    }
    
    func sendMessage(_ message: Message) {
        do {
            let data = try JSONEncoder().encode(message)
            try session.send(data, toPeers: connectedPeers, with: .reliable)
        } catch {
            print("Failed to send message: \(error)")
        }
    }
    
    func broadcastSOS(latitude: Double?, longitude: Double?) {
        let sosMessage = Message(
            id: UUID().uuidString,
            senderId: peerId.displayName,
            content: "🚨 SOS - EMERGENCY",
            messageType: .sos,
            latitude: latitude,
            longitude: longitude,
            timestamp: Date().timeIntervalSince1970
        )
        sendMessage(sosMessage)
    }
}

extension MeshManager: MCSessionDelegate {
    func session(_ session: MCSession, peer peerID: MCPeerID, didChange state: MCSessionState) {
        DispatchQueue.main.async {
            switch state {
            case .connected:
                if !self.connectedPeers.contains(peerID) {
                    self.connectedPeers.append(peerID)
                }
                self.isConnected = true
            case .connecting:
                break
            case .notConnected:
                self.connectedPeers.removeAll { $0 == peerID }
                if self.connectedPeers.isEmpty {
                    self.isConnected = false
                }
            @unknown default:
                break
            }
        }
    }
    
    func session(_ session: MCSession, didReceive data: Data, fromPeer peerID: MCPeerID) {
        do {
            let message = try JSONDecoder().decode(Message.self, from: data)
            DispatchQueue.main.async {
                self.receivedMessages.append(message)
            }
        } catch {
            print("Failed to decode message: \(error)")
        }
    }
    
    func session(_ session: MCSession, didReceive stream: InputStream, withName streamName: String, fromPeer peerID: MCPeerID) {}
    func session(_ session: MCSession, didStartReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, with progress: Progress) {}
    func session(_ session: MCSession, didFinishReceivingResourceWithName resourceName: String, fromPeer peerID: MCPeerID, at localURL: URL?, withError error: Error?) {}
}

extension MeshManager: MCNearbyServiceAdvertiserDelegate {
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didReceiveInvitationFromPeer peerID: MCPeerID, withContext context: Data?, invitationHandler: @escaping (Bool, MCSession?) -> Void) {
        invitationHandler(true, session)
    }
    
    func advertiser(_ advertiser: MCNearbyServiceAdvertiser, didNotStartAdvertisingPeer error: Error) {
        print("Failed to advertise: \(error)")
    }
}

extension MeshManager: MCNearbyServiceBrowserDelegate {
    func browser(_ browser: MCNearbyServiceBrowser, foundPeer peerID: MCPeerID, withDiscoveryInfo info: [String : String]?) {
        browser.invitePeer(peerID, to: session, withContext: nil, timeout: 10)
    }
    
    func browser(_ browser: MCNearbyServiceBrowser, lostPeer peerID: MCPeerID) {
        DispatchQueue.main.async {
            self.connectedPeers.removeAll { $0 == peerID }
        }
    }
}
```

### 3. Message Model

```swift
// Message.swift
import Foundation

enum MessageType: String, Codable {
    case text
    case sos
    case location
    case emergencyInfo
}

struct Message: Codable, Identifiable {
    let id: String
    let senderId: String
    let content: String
    let messageType: MessageType
    let latitude: Double?
    let longitude: Double?
    let timestamp: TimeInterval
}
```

### 4. Main View

```swift
// ContentView.swift
import SwiftUI

struct ContentView: View {
    @EnvironmentObject var meshManager: MeshManager
    @State private var sosActive = false
    @State private var showLocation = false
    
    var body: some View {
        NavigationView {
            VStack(spacing: 20) {
                // Status
                HStack {
                    Circle()
                        .fill(meshManager.isConnected ? Color.green : Color.red)
                        .frame(width: 12, height: 12)
                    Text(meshManager.isConnected ? "Mesh Active" : "Disconnected")
                    Spacer()
                    Text("\(meshManager.connectedPeers.count) peers")
                }
                .padding()
                .background(Color.gray.opacity(0.2))
                .cornerRadius(10)
                
                Spacer()
                
                // SOS Button
                Button(action: toggleSOS) {
                    ZStack {
                        Circle()
                            .fill(sosActive ? Color.red : Color.darkRed)
                            .frame(width: 200, height: 200)
                        VStack {
                            Text("SOS")
                                .font(.system(size: 48, weight: .bold))
                                .foregroundColor(.white)
                            Text(sosActive ? "TAP TO CANCEL" : "TAP FOR HELP")
                                .font(.caption)
                                .foregroundColor(.white.opacity(0.8))
                        }
                    }
                }
                .buttonStyle(PlainButtonStyle())
                
                Spacer()
                
                // Quick Actions
                HStack(spacing: 30) {
                    QuickActionButton(icon: "location.fill", label: "Share Location") {
                        shareLocation()
                    }
                    QuickActionButton(icon: "person.fill", label: "Medical ID") {
                        showMedicalID()
                    }
                    QuickActionButton(icon: "battery.full", label: "Battery") {
                        showBatteryInfo()
                    }
                }
            }
            .padding()
            .navigationTitle("Emergency Mesh")
        }
    }
    
    func toggleSOS() {
        sosActive.toggle()
        if sosActive {
            meshManager.broadcastSOS(latitude: 0, longitude: 0) // Add real location
        }
    }
    
    func shareLocation() {
        // Implement location sharing
    }
    
    func showMedicalID() {
        // Show medical ID
    }
    
    func showBatteryInfo() {
        // Show battery info
    }
}

struct QuickActionButton: View {
    let icon: String
    let label: String
    let action: () -> Void
    
    var body: some View {
        VStack {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.title2)
                    .frame(width: 50, height: 50)
                    .background(Color.blue.opacity(0.2))
                    .foregroundColor(.blue)
                    .cornerRadius(25)
            }
            Text(label)
                .font(.caption)
        }
    }
}
```

### 5. Info.plist Configuration

```xml
<!-- Add to Info.plist -->
<key>NSLocalNetworkUsageDescription</key>
<string>This app uses local network to communicate with nearby devices in emergency situations.</string>

<key>NSBonjourServices</key>
<array>
    <string>_emergencymesh._tcp</string>
</array>

<key>NSLocationWhenInUseUsageDescription</key>
<string>Location is used to share your position with rescuers in emergency situations.</string>

<key>NSLocationAlwaysAndWhenInUseUsageDescription</key>
<string>Location is used to share your position with rescuers even when app is in background.</string>
```

### 6. Entitlements

```xml
<!-- EmergencyMesh.entitlements -->
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLATLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>com.apple.developer.networking.multipeer</key>
    <true/>
</dict>
</plist>
```

## Cross-Platform Bridge Protocol

### Message Format (JSON)

```json
{
    "id": "uuid-string",
    "senderId": "device-name",
    "content": "message content",
    "messageType": "sos|text|location",
    "latitude": 40.7128,
    "longitude": -74.0060,
    "timestamp": 1677635200
}
```

### Connection Flow

1. **Android** creates WiFi Direct hotspot
2. **iOS** discovers via Multipeer Connectivity
3. **Handshake** via Bluetooth LE advertising (optional)
4. **Communication** over established P2P connection

## Limitations

| Feature | Android | iOS | Notes |
|---------|---------|-----|-------|
| WiFi Direct | ✅ | ❌ | iOS doesn't support WiFi Direct |
| Multipeer Connectivity | ❌ | ✅ | Apple proprietary |
| Bluetooth LE | ✅ | ✅ | Common bridge protocol |
| Multi-hop | ✅ | ❌ | iOS limited to direct connections |
| Range | 30-100m | 30-100m | Similar WiFi range |

## Bridge Solution

Since iOS doesn't support WiFi Direct, use one of these approaches:

### Option 1: Bluetooth LE Bridge
- Android advertises via BLE
- iOS scans and connects
- Messages relayed through BLE
- Limited bandwidth but works

### Option 2: Local WiFi Hotspot
- Android creates Local Only Hotspot
- iOS joins as regular WiFi client
- Communication over TCP/UDP
- Better bandwidth

### Option 3: Hybrid
- BLE for discovery
- WiFi for data transfer
- Best of both

## Next Steps

1. Implement BLE discovery on Android
2. Create iOS BLE scanner
3. Define handshake protocol
4. Test cross-platform messaging
5. Add encryption for cross-platform

---

**Note**: iOS devices can only communicate directly (no multi-hop). Android mesh can relay iOS messages through multiple Android devices.
