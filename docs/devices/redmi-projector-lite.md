# Redmi Projector Lite

Model `Projector_10`, Hisilicon `Hi3751V350`, Android 9 (SDK 28), firmware `1.21`.

## Home key

The firmware's normal `HOME` dispatch force-stops the process currently in front. With Aerial as
the default Home, that process is Aerial itself, so Home becomes a 2-3 second black screen while
Aerial cold-starts.

The firmware installs `com.cvte.factory.at`, whose presence identifies this dispatch. The guard
requests key filtering and handles Home itself even while Aerial is the default Home. Volume keys
remain with the firmware so they do not enter the guard's synthetic volume path.

The paired Xiaomi Bluetooth voice remote identifies as `小米蓝牙语音遥控器`, vendor `0x2717`,
product `0x32b8`, and declares `SOURCE_GAMEPAD`. It is excluded from gamepad detection; otherwise
the guard intentionally stops filtering keys outside Aerial and the vendor force-stop returns.

## Settings

No activity answers `ACTION_SETTINGS`. Control Center's Settings entry opens
`com.android.settings/com.asu.settings.TvMainActivity` directly.

## Verification

On Aerial 1.76, with Aerial both in front and after opening another app:

- Home returns to Aerial with the same process ID.
- A screen recording contains no black transition frame.
- Control Center's Settings entry opens the vendor settings activity.
- `testStandardReleaseUnitTest` and `assembleStandardRelease` pass.

To test the key path from a computer, inject Linux `KEY_HOME` (102) into the remote's input device.
`adb shell input keyevent HOME` bypasses accessibility key filtering and does not reproduce the
firmware path.

## Redmi Projector 4 / 5 Pro (mt5873, Android 13; issues #684 #688)

With the standard flavor set as the default Home, these firmwares stick at the boot animation from
1.47 on (factory reset needed). A reporter on the Projector 4 (firmware 1.33) built a guard-shaped
copy of 1.36 (no HOME declaration, PatchWall stays the default Home, key filter always on) and a
cold reboot finished in 36 s: the system started `com.mitv.tvhome`, the guard brought Aerial back.
The official guard flavor has not been tried there yet. The same report found that Home on this
firmware force-stops the app in front (`moveActivityTaskToBack: task.isSystemApp`, then
`Force stopping app.orchard.tv`) unless the filter takes Home first, and that with the filter on a
held volume key moves one step only: on mt5873 a Home without the flash and a held volume key
exclude each other.
