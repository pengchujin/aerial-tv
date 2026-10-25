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

## Native source names

The stock source picker labels the remaining inputs TV, AV and DTMB.
The framework exposes driver names instead; the NSOP0 profile matches the
stock labels without changing these inputs' routes:

| Hardware id | Framework label | Stock label |
| --- | --- | --- |
| 3 | NovatekAtvInput | TV |
| 1 | NovatekDVBT | DTMB |
| 0 | CVBSTvInput | AV |

Aerial's additional USB media-player shortcut is retained.

## Validation

A release build containing the HDMI correction was installed under an
isolated test package, preserving the official 1.47 installation and its
personalization. The owner confirmed HDMI 1 was shown and selecting it
produced the PS5 picture and audio. The test package was then removed and
the official app restored as Home. The TV/DTMB/AV labels above were checked
against a screenshot of Xiaomi's native picker; this subsequent label-only
change has not been installed on the TV.

CEC, TV/DTMB/AV playback and moving the cable through all physical HDMI
sockets have not been validated. No factory settings or system packages
were changed for these checks.
