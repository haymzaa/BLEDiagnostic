BY haymzaa😚
# BLE Diagnostic

A native Android diagnostic app written in Kotlin with Jetpack Compose. It passively listens for Bluetooth Low Energy (BLE) advertisements nearby and shows each device in a list and on a radar. It's a learning project for understanding how Android's BLE scanner APIs work.

## What it does

- **Scans BLE advertisements** using `BluetoothLeScanner` in low-latency mode. It runs as a foreground service with a persistent notification.
- **Shows each device's details:**
  - Advertised name (if the device includes one)
  - MAC address
  - OUI (the first three bytes of the MAC, which identify the manufacturer for public addresses)
  - Smoothed RSSI in dBm
  - Estimated distance in metres
  - A "likely randomized" flag for addresses that look locally administered. Phones and earbuds often rotate these, so their OUI doesn't identify a vendor.
- **Estimates distance** from RSSI with a log-distance path-loss model. Distance is rough, since RSSI can swing by several dB.
- **Draws a radar.** Distance sets how far a dot sits from the centre, and colour shows signal strength, from red (weak) to green (strong). The angle is a stable placement based on the address, not a real direction.
- **Removes stale devices** that haven't been seen for 10 seconds.

## Requirements

- Android 12 or newer (minimum SDK 31)
- A physical phone with Bluetooth LE. Emulators don't support BLE scanning.
- Bluetooth on, and Location turned on in system settings

## Permissions

The app asks for:

- **Nearby devices** (`BLUETOOTH_SCAN`) to scan for BLE advertisements
- **Precise location** (`ACCESS_FINE_LOCATION`), which Android requires for scan results
- **Notifications**, for the foreground-service notification (Android 13+)

Approximate location isn't enough for scanning, so the app reports "Permission denied" in that case.

## Install on a Samsung phone (APK)

These steps work on Galaxy phones such as the A25 and A35. Menu names vary a little between One UI versions.

1. **Get the APK.**
   - Option A: Open the project in Android Studio and choose **Build > Build App Bundle(s) / APK(s) > Build APK(s)**. The file is at `app/build/outputs/apk/debug/app-debug.apk`.
   - Option B: Download it from the repo's **Releases** page, if one is published.
2. **Move the APK to the phone.** Use USB, Google Drive, or send it to yourself by email, then download it on the phone.
3. **Allow installs from that app.** Go to **Settings > Apps**, choose the app you'll open the file with (for example, **My Files** or **Chrome**), then tap **Install unknown apps** and turn on **Allow from this source**. On some One UI versions, this is under **Settings > Security and privacy > Install unknown apps**.
4. **Install the APK.** Open **My Files > Downloads**, tap `app-debug.apk`, then tap **Install**.
5. **Handle the Play Protect warning.** Because the app isn't from the Play Store, Google Play Protect may show a warning. Tap **More details**, then **Install anyway**.
6. **Turn off the install permission.** Go back to step 3 and turn off **Allow from this source** once the install finishes.

## First run

1. Open **BLE Diagnostic** and tap **Start scan**.
2. Grant **Nearby devices**, **Precise location**, and **Notifications** when prompted.
3. The status should read "Scanning" and a notification should appear.
4. Nearby BLE devices should appear in the list and on the radar within a few seconds.
5. Tap **Stop scan**, or the notification's **Stop** action, to end scanning.

## Build from source

1. Clone the repo and open it in Android Studio.
2. Let Gradle sync.
3. Run `./gradlew assembleDebug` or press **Run** on a connected phone with USB debugging enabled.

## Known limitations

- The app doesn't yet react to Bluetooth being turned off during a scan.
- The "Scanning" status is set when you tap the button, not from the service's real state. If you stop from the notification, the button and status can be out of date.
- Distance and direction are estimates. Walls, bodies, and antenna orientation all change the results.
- Results depend on what nearby devices advertise. Many devices only advertise in pairing mode.

## Privacy

This app only listens to advertisements that nearby devices are already broadcasting. It doesn't connect to devices. Use it on devices you own or have permission to observe, and don't use it to track people.
