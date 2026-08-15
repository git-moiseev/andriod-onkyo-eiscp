# TODO

## Bind eISCP discovery to the local Wi-Fi network

Current discovery sends an eISCP broadcast to `255.255.255.255:60128` through
the system default route. When a VPN is active, Android may route this packet
through the VPN instead of the Wi-Fi interface, so the local receiver is not
found.

Planned implementation:

- Use `ConnectivityManager.allNetworks` and `NetworkCapabilities` to find the
  active network with `TRANSPORT_WIFI`.
- Read its IPv4 address and prefix length from `LinkProperties.linkAddresses`.
- Calculate the directed broadcast address for that subnet, for example
  `192.168.1.73/24` → `192.168.1.255`.
- Create an unconnected `DatagramSocket` and bind it specifically to the Wi-Fi
  `Network` with `Network.bindSocket(socket)`.
- Send discovery to the calculated subnet broadcast and optionally also to
  `255.255.255.255` as a fallback.
- Bind direct UDP information requests to the same Wi-Fi network.
- Open the receiver TCP connection through the same `Network`, using its socket
  factory or `Network.bindSocket`, so discovery and control follow the same
  route.
- Keep the existing manual-IP fallback and useful diagnostic logging.
- Test with VPN off, a normal VPN, mobile data enabled, multiple Wi-Fi/network
  interfaces, and an Android emulator.

Known limitation: an always-on VPN configured to block connections outside the
VPN may prevent access to the local Wi-Fi network even when sockets are bound
explicitly.
