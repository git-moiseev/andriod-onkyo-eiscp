# Onkyo Remote for Android

Native Android remote control for Onkyo and Integra receivers. It communicates through eISCP, the Ethernet version of the Integra Serial Control Protocol documented by Onkyo Corporation. The protocol implementation and command mapping are based on the excellent Python project [`miracle2k/onkyo-eiscp`](https://github.com/miracle2k/onkyo-eiscp), rewritten here as a native Kotlin application with no Python runtime or web server required.

## What it can do

- Automatically discover compatible eISCP receivers through a selected local network interface, or connect to a manually entered IP address.
- Keep finding a receiver through Auto-discover if its DHCP address changes.
- Turn the receiver on and off.
- Select an input with a short tap.
- Give every input a custom name with a long press; names are remembered between launches and application updates.
- Arrange the input buttons by long-pressing the `INPUTS` label and dragging any input directly to any position.
- Control volume with a large hardware-style knob, haptic feedback, and protection against dangerous accidental volume jumps.
- Switch between Direct, Stereo with Music Optimizer ON, and Stereo with Music Optimizer OFF by tapping the center of the knob.
- Mute or restore playback with a long press on the knob center.
- Follow power, input, volume, mute, and sound-mode changes made on the receiver or another remote.
- Reconnect automatically after network changes and when the application returns to the foreground, in both Auto-discover and Static IP modes.
- Run in Demo mode without a receiver, using a long press on the `PHONES` socket.

![Onkyo Remote](doc/screen.jpg)

## Download

[Download the latest release](https://github.com/git-moiseev/onkyo-eiscp-android/releases)

The interface deliberately resembles a dark hi-fi front panel rather than a standard Android control screen. Its main control is a custom-drawn 270-degree volume knob with hardware-style lighting, texture, haptics, and touch gestures.

## Features

### Receiver connection

- Automatic receiver discovery using the standard eISCP UDP broadcast on port `60128`, with an optional user-selected local network interface.
- VPN-tolerant unicast discovery fallback when network binding is denied or broadcast replies are blocked.
- Manual IP address configuration.
- Persistent Auto-discover mode for receivers whose DHCP address can change.
- Receiver model and identifier obtained from the eISCP discovery response.
- Persistent TCP connection for commands and unsolicited receiver updates.
- Automatic reconnect after connection loss, VPN/network changes, and return to the foreground in both Auto-discover and Static IP modes.
- Foreground retry backoff from one second to a maximum interval of 15 seconds.
- Connection setup by long-pressing the status display.
- Receiver and playback state shown on a hardware-style LED display.

### Main controls

- Receiver power on/off.
- Six main-zone input selectors with an active-input LED.
- Main-zone volume from `0` to `80` using a custom Compose `Canvas` knob.
- Approximately 270 degrees of knob rotation with radial ticks and an active blue scale.
- Haptic feedback while changing volume.
- Mute/unmute from the center of the knob.
- Listening Mode and Music Optimizer control.
- Live synchronization with changes received from the receiver.

### Knob center gestures

Short taps cycle through these sound profiles:

1. `Direct` — `LMD01`, shown with a white musical note.
2. `Stereo, Music Optimizer ON` — `LMD00` + `MOT01`, shown in blue.
3. `Stereo, Music Optimizer OFF` — `MOT00`, shown in green.

A long press toggles mute:

- while muted, the center displays a red mute icon;
- short taps are ignored while muted;
- another long press restores playback and the musical-note indicator.

The status display shows the corresponding state. Long lines scroll together with the receiver model like a hardware front-panel display:

```text
DTM-40.4 Direct
DTM-40.4 Stereo, Music Optimizer On
DTM-40.4 Stereo, Music Optimizer Off
DTM-40.4 Mono
DTM-40.4 Muted
DTM-40.4 Standby
```

Only `Muted` is displayed in red; other status text remains green.

When receiver power is off but its network interface remains reachable, the display shows `{MODEL} Standby`. Audio-path states are shown only while the receiver is powered on.

Mono is intentionally not included in the knob's sound-profile cycle, but the display shows `Mono` when that mode is selected from the receiver or its physical remote.

### Configurable inputs

Long-press any input button to rename it. Renaming is available while the receiver is on, in standby, disconnected, or while Demo mode is active.

Long-press the `INPUTS` label to open the `Arrange inputs` dialog. Long-press a row, drag it directly to any position in the list, and release it; then press `Done`. `Reset` restores the default order.

Default buttons and eISCP input codes:

| Default name | Receiver input | eISCP command |
|---|---|---|
| DVD | `dvd` | `SLI10` |
| TV | `tape-1` | `SLI20` |
| VIDEO1 | `video1` | `SLI00` |
| VIDEO2 | `video2` | `SLI01` |
| PS3 | `video3` | `SLI02` |
| CD | `cd` | `SLI23` |

Custom names, input order, the manual receiver IP, the selected discovery interface, and the Auto-discover setting are stored in Android `SharedPreferences`. The manual IP is retained but ignored while Auto-discover is enabled. These settings survive normal application updates as long as the package ID and signing key remain unchanged and the application is updated in place rather than uninstalled.

### Demo mode

Long-press the decorative `PHONES` socket to enter or leave Demo mode. Demo mode allows every control to work without receiver hardware and sends no network commands. The display uses `DEMO` instead of a receiver model while retaining the current sound state, for example `DEMO Direct` or `DEMO Muted`.

### Volume safety

The volume knob rejects a drag if it starts more than five volume points away from the receiver's current value. This prevents an accidental jump from a low volume to a dangerous level when the first touch lands elsewhere on the dial.

## Android requirements

| Setting | Value |
|---|---|
| Minimum Android API | 26 (Android 8.0) |
| Compile SDK | 37 |
| Target SDK | 37 |
| Java/JVM target | 17 |

Required permissions:

- `INTERNET` for eISCP UDP/TCP traffic;
- `ACCESS_NETWORK_STATE` and `ACCESS_WIFI_STATE` for network awareness;
- `ACCESS_LOCAL_NETWORK` for local receiver access on platforms that enforce this runtime permission.

On devices running API 37 or newer, the application requests local-network permission before discovery or connection. The application cannot discover or control a receiver when this permission is denied.

## Receiver requirements

- The phone and receiver must be reachable on the same local network.
- The receiver must support Ethernet eISCP, normally on TCP/UDP port `60128`.
- To power on a receiver from standby, enable its network standby option. On many Onkyo/Integra models it is named **Setup → Hardware → Network → Network Control**.
- Supported commands vary between receiver models and firmware versions. Power, volume, mute, input selection, `LMD`, and `MOT` are known to work with the Integra DTM-40.4 used during development, but another receiver may omit or interpret some commands differently.

## Discovery limitations

By default, Discovery sends an eISCP packet to `255.255.255.255:60128` through the system route. In the receiver connection dialog, a specific local interface can be selected instead, for example `Wi-Fi (wlan0) - 192.168.1.73`. The application then attempts to bind UDP to the corresponding Android network and sends to that interface's subnet broadcast address. The selection is stored by interface name, so it remains valid when the phone receives a new IP address.

Some VPNs reject direct Android network binding with `EPERM` but still permit routed local traffic. This does not abort discovery: the application continues with broadcast and, if no receiver replies, falls back to unicast eISCP probes across the selected subnet. The subnet prefix is read directly from the selected interface; it is not guessed. To avoid sending thousands of packets, unicast probing runs only when the reported subnet is no larger than `/22` (supported range `/22` through `/30`).

Discovery may still fail when:

- an always-on VPN blocks every connection outside the VPN;
- the phone is using mobile data or a different Wi-Fi/VLAN;
- Wi-Fi client isolation is enabled;
- the router or access point blocks broadcast traffic;
- the wrong local interface is selected or the saved interface is unavailable;
- the application runs in an emulator behind NAT.

Ensure that the phone is connected to the receiver's local network and select the corresponding interface in the connection dialog. Many VPN configurations work through the unicast fallback, but a VPN policy that blocks all traffic outside the tunnel must be disabled or configured to allow local-network access. If discovery still fails, configure the receiver IP manually. Discovery selects a previously known receiver by its identifier when possible; if several unknown receivers answer simultaneously, automatic selection may require manual configuration.

## Other limitations

- Only the main receiver zone is controlled.
- The volume range is fixed at `0..80`.
- The six input codes are fixed; only their visible names are configurable.
- There is no background service. The TCP connection is closed when the activity stops and recreated when the application returns to the foreground.
- Demo mode is not persisted across application restarts.
- eISCP Audio Information (`IFA`) is not displayed. Some receivers, including the tested DTM-40.4, do not return it.
- Wireless debugging can noticeably reduce phone performance and should be disabled after development testing.

## Build and run

### Android Studio

1. Open the repository root in Android Studio.
2. Use the bundled Android Studio JDK 17.
3. Allow Gradle to synchronize dependencies.
4. Select a physical phone on the receiver's Wi-Fi network.
5. Press **Run**, or choose **Build → Build APK(s)**.

The debug APK is written to:

```text
app/build/outputs/apk/debug/app-debug.apk
```

If Gradle 9.5 is installed and available on `PATH`, the equivalent command is:

```text
gradle :app:assembleDebug
```

Install new APKs over the existing application to preserve settings. Debug APKs built on different computers may use different debug signing keys and therefore may not update each other. Production releases should always use the same securely backed-up release keystore.

## Protocol overview

Commands such as `PWR01` are encoded as an ISCP message (`!1PWR01\r`) and wrapped in a 16-byte eISCP frame containing the `ISCP` magic, header size, payload size, protocol version, and reserved bytes.

At connection time the application queries:

- receiver/device information (`ECNQSTN`);
- power (`PWRQSTN`);
- volume (`MVLQSTN`);
- mute (`AMTQSTN`);
- selected input (`SLIQSTN`);
- listening mode (`LMDQSTN`);
- Music Optimizer (`MOTQSTN`).

Unsolicited eISCP messages are processed through the same state-update path, so changes made with the physical receiver controls or another remote are reflected in the UI.

## Acknowledgements

The command naming and initial input mapping were informed by the [`onkyo-eiscp`](https://github.com/miracle2k/onkyo-eiscp) project and its machine-readable eISCP command dictionary.
