# Onkyo Remote for Android

Native Android prototype for controlling an Onkyo / Integra receiver over eISCP.

## Implemented

- UDP eISCP receiver discovery on port 60128
- Persistent TCP eISCP connection
- Receiver state updates from unsolicited eISCP messages
- Power on/off
- Main-zone volume
- Mute
- Input selection using the same default inputs as `git-moiseev/onkyo-eiscp`

## Open in Android Studio

1. Open the `OnkyoRemoteAndroid` directory.
2. Let Android Studio sync Gradle dependencies.
3. Select a physical Android phone connected to the same LAN/Wi-Fi as the receiver.
4. Run the app.
5. If the receiver is in standby and cannot be discovered/started, enable **Setup → Hardware → Network → Network Control** on the receiver.

## Notes

The project intentionally uses direct sockets instead of embedding Python/Flask.

Discovery currently sends the standard eISCP discovery packet to `255.255.255.255:60128`. Some Wi-Fi/router configurations suppress limited broadcasts. If discovery does not find the receiver, the next useful addition is a manual IP field plus subnet-specific broadcast calculation.

The six initial input buttons reproduce the existing web app's default mapping:

- VIDEO1 → SLI00
- VIDEO2 → SLI01
- PS3 / VIDEO3 → SLI02
- DVD → SLI10
- TV / TAPE-1 → SLI20
- CD → SLI23

## Protocol framing

Outgoing commands are encoded as an ISCP message such as `!1PWR01\r`, wrapped in a 16-byte eISCP header with magic `ISCP`, header size 16, payload length, version 1, and three reserved zero bytes.
