# Clean-system feasibility: Xiaomi MiTV-MFTR0

Read-only audit, 2026-10-24. This device reports Android 14, codename freeguy,
firmware OS3.0.106.0.UFTMATV, `ro.boot.vbmeta.device_state=locked` and
`ro.boot.verifiedbootstate=green`. `ro.oem_unlock_supported` is empty: absence is
not evidence that an unlock path exists. Raw output is retained locally.

Aerial is an Android launcher application, not a kernel, hardware abstraction layer,
or firmware distribution. A new AOSP-based TV OS could incorporate this launcher,
but would additionally need a supported device target, vendor drivers/services,
boot-chain access and a tested recovery path. None has been established for this TV.

Xiaomi's [TV/box support page](https://www.mi.com/tw/support/article/KA-19750/)
states that user flashing is unsupported and official flash packages are not supplied.
Android's [bootloader documentation](https://source.android.com/docs/core/architecture/bootloader/locking_unlocking)
explains the locked-device restriction and that supported unlocking entails a data reset.
Phone unlocking instructions do not establish TV support.

Therefore this audit does not authorize or attempt bootloader unlocking, flashing,
or removal of system packages. Retain the firmware and build the clean daily experience
in Aerial. The firmware still owns HDMI/CEC, media codecs, DRM, power and system settings.
As documented in devices/xiaomi-mitv-mftr0.md, disabling PatchWall on this model causes
FallbackHome to remain on a black screen; the existing accessibility guard remains necessary.
