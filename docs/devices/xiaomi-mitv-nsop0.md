# Xiaomi MiTV-NSOP0 (furyroad, Novatek)

HDMI routing checked on a real TV with Android 9 (SDK 28), firmware
`22.7.26.1365`, Aerial 1.47 standard. See #563 (HDMI) and #65 (installation).

## Framework names are not the physical port numbers

With a PS5 connected, Xiaomi's source picker shows `PlayStation 5 / HDMI1`.
The connected framework input is instead
`com.novatek.hdmi3tvinput/.hdmi3TvInputService/HW6`; Aerial 1.47 displays
`Hdmi3TvInput` as connected and sends `input=24`, which opens HDMI2.

Explicitly starting `com.xiaomi.mitv.tvplayer/.ExternalSourceActivity` with
`--ei input` and reading the active session in `dumpsys tv_input` gives:

| Xiaomi player input | Active framework input | Physical port label |
| --- | --- | --- |
| 23 | `com.novatek.hdmi3tvinput/.hdmi3TvInputService/HW6` | HDMI 1 |
| 24 | `com.novatek.hdmi2tvinput/.hdmi2TvInputService/HW5` | HDMI 2 |
| 25 | `com.novatek.hdmi1tvinput/.hdmi1TvInputService/HW4` | HDMI 3 |

Each start returned `Status: ok` and the player stayed in the foreground.
Home returned to Aerial after the probe. The NSOP0 profile reverses the
labels and explicitly maps each hardware id to the player target. It keeps
the framework id unchanged, so connection status and saved source references
remain attached to the same input. Other Novatek models are not assumed to
share this mapping.

The active-session routes were tested without replacing the official APK.
The patched APK itself has not been installed on this TV: the local build
has a different signing key and cannot update the official installation.
PS5 video/audio, CEC and moving the cable through all physical sockets have
not been validated. No factory settings or system packages were changed
for the routing probe.
