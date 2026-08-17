# Onkyo Remote 1.0.2

## Highlights

- Added selectable network interfaces for Auto-discover. The connection dialog now lists active broadcast-capable IPv4 networks such as `Wi-Fi (wlan0) - 192.168.1.73`.
- Added reliable discovery while many VPN applications are active:
  - Discovery attempts to bind to the selected Android network.
  - A VPN rejection such as `EPERM` no longer aborts discovery.
  - If broadcast receives no reply, the application falls back to targeted unicast eISCP probes across the selected local subnet.
- Added automatic reconnection after network changes in both Auto-discover and Static IP modes.
  - Retries begin after one second and back off to a maximum interval of 15 seconds.
  - Retry activity stops while the application is in the background.
  - The delay resets after a successful connection.
- Clarified connection-mode behavior:
  - While Auto-discover is enabled, the manual IP field is disabled and visually dimmed.
  - The manual IP is retained for later use but ignored by Auto-discover.
  - A discovered address is displayed as status information rather than replacing the saved manual IP.
- Removed stale receiver model names from the disconnected display. Until a connection is established, the display shows `Long tap to connect`.

## Network behavior

- `Automatic (system route)` retains the original limited-broadcast behavior.
- Selecting a local interface sends to that interface's directed subnet broadcast.
- The interface choice is stored by system interface name, so it survives phone DHCP address changes.
- Unicast fallback is limited to subnet sizes from `/22` through `/30` to avoid excessive probing.
- Auto-discover was validated on a physical Android phone with VPN both enabled and disabled.

## Requirements and limitations

- Android 8.0 (API 26) or newer.
- The phone and receiver must be reachable on the same local network.
- Wi-Fi client isolation, VLAN separation, or an always-on VPN that blocks all traffic outside the VPN can still prevent discovery and control.
- Network Control or Network Standby must be enabled on the receiver to power it on from standby.
- Only the main zone and a fixed `0..80` volume range are currently supported.
- Command availability varies between Onkyo and Integra models.

## Installation

Install `OnkyoRemote.apk` over the previous version to preserve the receiver address, Auto-discover preference, selected network interface, input names, and input order. Do not uninstall the previous version first.

This APK is a debug-signed build. Android will install it as an update only if the existing application was signed with the same debug key.
