# Onkyo Remote 1.0.1

## Highlights

- Added customizable input-button ordering. Long-press `INPUTS`, then drag an input to any position. The order is saved between launches and application updates.
- Added a hardware-style scrolling status display for long text. The receiver model and playback state scroll together, while short messages remain stationary.
- Replaced abbreviated sound-mode labels with full descriptions: `Stereo, Music Optimizer On` and `Stereo, Music Optimizer Off`.
- Added display-only support for Mono mode selected on the receiver or physical remote. Mono is intentionally not included in the application's sound-mode cycle.
- Added `{MODEL} Standby` while the receiver is powered off but remains reachable over the network.
- Improved Demo status display: Demo mode uses `DEMO` instead of a receiver model and follows the same playback and standby rules.
- Expanded the README with a user-focused feature summary, Android requirements, limitations, gesture reference, and protocol acknowledgements.

## Controls

- Tap an input button to select it; long-press it to rename it.
- Long-press `INPUTS` to arrange the input buttons.
- Drag the volume knob to change volume. For safety, a drag starting more than five points from the current volume is ignored.
- Tap the knob center to cycle through Direct, Stereo with Music Optimizer On, and Stereo with Music Optimizer Off.
- Long-press the knob center to mute or restore playback.
- Long-press the status display to configure Auto-discover or a manual receiver IP address.
- Long-press the `PHONES` socket to enter or leave Demo mode.

## Requirements and limitations

- Android 8.0 (API 26) or newer.
- The phone and receiver must be reachable on the same local network.
- VPN, Wi-Fi client isolation, VLAN separation, or blocked broadcast traffic may prevent Auto-discover from working; manual IP configuration remains available.
- Network Control or Network Standby must be enabled on the receiver to power it on from standby.
- Only the main zone and a fixed `0..80` volume range are currently supported.
- Command availability varies between Onkyo and Integra models.

## Installation

Install `OnkyoRemote.apk` over the previous version to preserve the receiver address, Auto-discover preference, input names, and input order. Do not uninstall the previous version first.

This APK is a debug-signed build. Android will install it as an update only if the existing application was signed with the same debug key.
