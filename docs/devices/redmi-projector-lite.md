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
