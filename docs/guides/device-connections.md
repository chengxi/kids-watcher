# USB and Wi-Fi during agent-assisted setup

USB is not required for QR pairing or normal KidsMonitor operation. App
installation and developer testing are separate steps: those need an authorized
developer connection, which can be wired or wireless depending on the platform.
The developer computer does not need to remain connected once setup is finished.
Keep the hub running and the phones on its reachable home network.

| Step | USB requirement |
| --- | --- |
| Build apps | No phone connection; iOS builds use Mac/Xcode. |
| Install/test Android 11+ | Wireless debugging supports a cable-free developer connection. |
| Install/test an Android 10 parent | The legacy ADB-over-Wi-Fi route starts with USB. |
| Install/test an already Xcode-paired iPhone/iPad | Wi-Fi can carry installation, launch and tests. |
| First Xcode pairing on iOS/iPadOS 26 or earlier | Use a cable and approve Trust on the device. |
| First pairing with Apple's newer wireless Device Hub flow | Requires iOS/iPadOS 27+ and compatible Xcode; not verified here. |
| Scan a KidsMonitor pairing QR | No developer connection; the app contacts the hub over the LAN. |
| Monitoring/viewing after setup | No developer connection; phones communicate with the hub. |

There are two different pairings. **Developer pairing** authorizes ADB/Xcode to
install and inspect apps. **KidsMonitor pairing** uses the hub's QR to register
a monitor or parent. The hub QR does not enable developer access or install an
app. Public internet access helps the agent obtain dependencies, but does not
replace device authorization or LAN reachability.

## Android over Wi-Fi

[Android's official ADB guide](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi)
supports wireless deployment on Android 11+. The monitor requires Android 12+;
the parent also permits Android 10, which needs the older USB-first ADB route.

Have the user enable Developer options, open **Wireless debugging**, approve
the network, and choose **Pair device with pairing code**. The agent uses the
phone's displayed pairing endpoint and enters the code at the interactive prompt:

```bash
adb pair <phone-ip>:<pairing-port>
adb devices -l
```

If discovery does not connect automatically, use the connection endpoint on the
main Wireless debugging screen; its port can differ from the pairing port:

```bash
adb connect <phone-ip>:<connection-port>
adb devices -l
```

Use the exact device identifier reported by `adb devices -l` for the existing
install, launch and log commands in [installing-monitor.md](installing-monitor.md).
`adb -s <identifier>` works with a wireless device too. The debug payload helper
`tools/pair-device.sh` uses ADB without forcing USB, so it can use that same
authorized wireless connection; QR pairing does not need the helper.

Keep the phone and developer computer on a network that permits device-to-device
traffic. If discovery fails, check the current address/port and network isolation
before falling back to a cable. Do not enable or reconfigure debugging on active
monitors merely to inspect their existing hub connection.

## iPhone/iPad over Wi-Fi

First inspect the device's existing Xcode pairing and network transport:

```bash
xcrun devicectl list devices
xcrun devicectl device info details --device <device-identifier>
xcodebuild -version
```

An already paired, reachable device with Developer Mode enabled can be used as
the destination for the normal [iOS parent](ios-parent.md) or
[iOS monitor](ios-monitor.md) build/install workflow over Wi-Fi. Keep it unlocked
for launching and permission prompts. Signing/provisioning requirements still
apply.

For an unpaired device running iOS/iPadOS 26 or earlier, connect it to the Mac
once and approve **Trust This Computer**, then enable Developer Mode as required
by Xcode. After developer pairing, Wi-Fi can be used; do not unpair an already
working device to repeat that bootstrap. Apple's
[current Device Hub guide](https://developer.apple.com/documentation/xcode/managing-your-simulated-and-physical-devices-in-device-hub)
also documents first-time wireless pairing for iOS/iPadOS 27+, with compatible
Xcode. Check the actual installed versions and offered workflow before promising
a cable-free first installation.

## Agent preflight and verification

Inspect existing developer connections first. Prefer a working authorized Wi-Fi
connection, and request a cable only when the platform/bootstrap or a connection
failure requires it. The user still performs on-device trust, debugging,
permission and unlock steps; the agent handles the host tools and network checks.
Then install, display the [pairing QR](pairing-with-qr.md), and verify advancing
hub heartbeats. Device discovery alone is not installation or monitoring proof.

On October 14, 2026, the iPad Pro already had Developer Mode and Xcode pairing.
It ran iPadOS 26.6.1, with Xcode 26.5 on the Mac. `devicectl` reported
`transportType: localNetwork`; the parent app was installed and launched over
that connection, and the user scanned the Mac-displayed QR and reached the
dashboard. This verifies installation and hub pairing over Wi-Fi for an
already developer-paired iPad, not first-time cable-free Xcode pairing.

The connected Pixel 6 was using USB with Wireless debugging disabled. Android
wireless deployment was researched against official documentation but was not
enabled or tested during this check. Existing capture and pairings were preserved.
