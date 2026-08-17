# TODO

## Local-network discovery

Completed:

- [x] List active IPv4 interfaces that support broadcast.
- [x] Show a readable interface type, system name, and current IP address.
- [x] Allow the user to select a discovery interface or the system route.
- [x] Store the interface name across launches and phone DHCP address changes.
- [x] Bind discovery to the selected local IPv4 address.
- [x] Bind discovery to the corresponding Android `Network` when VPN policy permits it.
- [x] Send eISCP discovery to the selected subnet's directed broadcast address.
- [x] Use the selected interface for direct UDP receiver-information requests.
- [x] Continue safely when a VPN rejects `Network.bindSocket()` with `EPERM`.
- [x] Fall back to routed unicast eISCP probes when broadcast produces no reply.
- [x] Limit unicast probing to reasonable `/22` through `/30` subnets.
- [x] Retain the manual IP while Auto-discover is enabled, but clearly disable and ignore the field.
- [x] Reconnect automatically after network changes in both Auto-discover and Static IP modes.
- [x] Use bounded reconnect backoff while the application is in the foreground.
- [x] Avoid displaying a cached receiver model while disconnected.

Validated:

- [x] Auto-discover with VPN disabled.
- [x] Auto-discover with VPN enabled and local-network traffic permitted.
- [x] Reconnection after enabling or disabling VPN.
- [x] Static IP connection while VPN is enabled.
- [x] Physical Android phone on the receiver's Wi-Fi network.

Remaining compatibility tests:

- [ ] Mobile data active alongside Wi-Fi on additional Android vendors.
- [ ] USB Ethernet and multiple simultaneous broadcast-capable interfaces.
- [ ] Android emulator configurations that expose a reachable LAN interface.
- [ ] Always-on VPN configurations that block all connections outside the VPN.

Known limitation: Android or a VPN can prohibit all access outside the VPN. In
that policy mode neither broadcast nor routed unicast discovery can reach a
receiver on the physical LAN.
