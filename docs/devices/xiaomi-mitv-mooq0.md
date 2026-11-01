# Xiaomi MiTV-MOOQ0 (MediaTek, Android TV 11)

Checked by the owner on a real TV with Android 11 (SDK 30), Aerial 1.72
standard built from source, Home Guard on.

## PatchWall is `com.mitv.tvhome.atv`

This firmware is Android TV with Google's launcher (`com.google.android.tvlauncher`,
the Home the system resolves) and Xiaomi's PatchWall on top, in its own
package `com.mitv.tvhome.atv`. There is no `com.mitv.tvhome` on it. The Home
candidates are tvlauncher, Aerial, other installed launchers and
`com.android.tv.settings/.system.FallbackHome`; PatchWall is not one of them.

## The Mi key

The remote has no standard Home key. Its Mi key sends `KEYCODE_BUTTON_5` (192),
and `com.xiaomi.android.tvsetup.partnercustomizer` (`GlobalKeyReceiver.startPatchWall`)
starts PatchWall's home screen by component:

```
mCurrentFocus=Window{… com.mitv.tvhome.atv/com.mitv.tvhome.atv.app.PatchWallActivity}
```

`PatchWallActivity` declares `LEANBACK_LAUNCHER` but no Home category, so the
guard took it for an app the viewer had opened and left it over Aerial. It is
now listed in `HomeGuardService.FIRMWARE_HOMES_WITHOUT_FILTER`.

`adb shell input keyevent KEYCODE_HOME` went to Aerial before and after.

## Lost after a reboot

The guard binds before the user is unlocked. While locked, `getActivityInfo`
finds only Direct Boot aware activities, so `isActivity` answered "missing" for
`PatchWallActivity` and cached it; the refresh at unlock reused that answer and
the diagnostics listed no `guard keeps away com.mitv.tvhome.atv`. The cache is
now cleared at unlock. The same applied to every entry of
`FIRMWARE_HOMES_WITHOUT_FILTER` on Android 7 and later.

## Validation (2026-10-30)

- After `adb reboot`, the diagnostics list `guard keeps away com.mitv.tvhome.atv:
  com.mitv.tvhome.atv.app.PatchWallActivity`.
- Mi key on the remote, three presses after that reboot: Aerial back 0.15–0.43 s
  after partnercustomizer started PatchWall (`stock home com.mitv.tvhome.atv
  came to the front; bringing Aerial back`). Injected with
  `adb shell input keyevent 192`: the same path, 0.25–0.9 s. The first press
  after a boot took 4.7 s: PatchWall starts cold, and the guard acts on its
  window.
- PatchWall's input source popup (`.app.tv.InputSourcePopupActivity`, action
  `com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP`) opens and stays; Back
  returns to Aerial.
- Standby (`KEYCODE_SLEEP`, 70 s, woken with the remote's power key): the
  firmware showed tvlauncher, and the guard brought Aerial back in 0.5 s. ADB
  is unreachable while the TV is in standby, so it cannot be woken over ADB.
- `adb reboot`, twice: the system started Aerial as Home and it stayed in
  front. PatchWall did not come up during either boot, so the new entry was
  not exercised at power-on.

Not checked: a power-on where PatchWall does show.
