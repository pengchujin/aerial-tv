# Adapting Aerial to a TV (for AI agents)

Aerial (an open-source TV launcher, https://github.com/pengchujin/aerial-tv ) is installed on the user's TV, but something is off: Home does not come back to Aerial, standby or a restart ends on the old launcher, an input opens the wrong port, the brightness slider does nothing. Your job is to **find out on the TV what is wrong, fix on the spot what can be fixed, and write up the evidence for the rest** for the user to send to the maintainer, who has only two TVs.

If Aerial is not installed yet, install it first with https://aerial.qust.me/en/agent.md and pass its acceptance check.

## Rules

1. **Run the commands yourself** on the user's computer and read their real output; never claim success you did not see. A chat-only agent should ask the user to switch to a desktop agent that can run commands.
2. **Do not uninstall or disable any system app** (the old launcher included), delete files on the TV, factory-reset it, or change system settings not listed below.
3. **Don't install any app other than Aerial** (no app stores, ADB enablers or other tools). To reinstall or update Aerial, use only `install.sh` below or the official APK on aerial.qust.me.
4. Ask the user before a standby test or a restart (`STANDBY=1`, `REBOOT=1`, `adb reboot`).
5. Note the result before and after every switch you try; set back the ones that did not help.
6. When the user has to look at the TV or press a key, say exactly what and which.
7. Show the user the full text before posting anything to GitHub, and post only once they agree.

## Step 1: connect

```
adb connect <IP>:5555
adb devices
```

The TV must show as `device`. For `unauthorized` or a failed connection, see steps 1 and 2 of https://aerial.qust.me/en/agent.md .

## Step 2: collect and self-test

macOS / Linux (on Windows, in Git Bash or WSL):

```bash
curl -fsSL https://aerial.qust.me/tools/report.sh | TV=<IP> bash
curl -fsSL https://aerial.qust.me/tools/selftest.sh | TV=<IP> bash
```

- `report.sh` only reads, and writes `aerial-report-<model>.md`: model, firmware, Home and Home Guard state, inputs, log.
- `selftest.sh` presses Home, opens the TV's settings, and checks that Aerial comes back; it writes `aerial-selftest-<model>.md` with `PASS`, `FAIL` or `SKIP` per check. With the user's consent, run it again with:
  - `STANDBY=1`: standby for 70 s, then wake; Xiaomi turns ADB off in standby, and the script asks for the remote's power key.
  - `REBOOT=1`: restart and log what is in front each second for the first minute.
  - `INPUTS=1`: open each of Aerial's inputs as its tile does, with a screenshot of each. HDMI often captures black; what the user sees counts.

## Step 3: what to do with a failed check

Every switch goes through Aerial's ADB receiver (only the ADB shell can send it):

```
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver <arguments>
```

written `CONFIG <arguments>` below.

### Wrong inputs (wrong port, a port that is not there, wrong name)

These are fixed on the spot, with no new release. Aerial keeps each TV's corrections as a small JSON (the device profile), keyed by the input's hardware id (`.../HW5` is 5):

| Field | Does |
|---|---|
| `"hidden": [8]` | Hide HW8 (no such port on this TV) |
| `"labels": {"6": "HDMI 1"}` | Show HW6 as HDMI 1 |
| `"xiaomiSources": {"6": 23}` | Xiaomi: open HW6 with tvplayer source id 23 (HDMI is usually 19-32) |
| `"ignoresPictureBacklight": true` | The TV has `picture_backlight` but does not follow it: leave brightness to the TV's own slider |
| `"ignoresScreenBrightness": true` | No `picture_backlight`, and the system's `screen_brightness` takes writes without the picture changing (Hisense VIDAA and others): the brightness row opens the TV's own picture settings |

1. List the inputs: `CONFIG --ez list_sources true`; after `data=` is JSON with `id`, `label`, `hw`, `firmwareLabel`, `xiaomiInput` and `shown`.
2. Open each: `CONFIG --es open_source '<id>'`, ask the user which port on the TV it switched to (or none), then `adb shell input keyevent KEYCODE_HOME`.
3. Write the corrections. The JSON has quotes and spaces, so put the whole command in double quotes, the JSON in single quotes and its double quotes as `\"` (a macOS / Linux terminal; Git Bash on Windows), or the TV's shell eats the quotes:
   ```
   adb shell "am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es device_profile '{\"hidden\":[8],\"labels\":{\"6\":\"HDMI 1\"},\"xiaomiSources\":{\"6\":23,\"5\":24}}'"
   ```
   The `device_profile=...` in the output is what was stored and should match. It **replaces** this TV's corrections whole, so start from the current one (the diagnostics' "Device profile"). Invalid JSON is not stored, and the output says `not valid JSON`.
4. Repeat step 2 until every port is right. On Xiaomi, change one port at a time, from 23 up.
5. The **Device profile** section at the end of `adb shell dumpsys activity app.orchard.tv/.MainActivity report` (origin `ThisTv`) is what goes to the maintainer.
6. To undo: `CONFIG --es device_profile default`.

### Home does not come back (`home_on_aerial` / `home_from_app` fail)

1. `guard` failed too: Home Guard is not running. Run `curl -fsSL https://aerial.qust.me/install.sh | TV=<IP> bash -s repair`, or `adb shell am force-stop app.orchard.tv`, wait 3 s, `adb shell am start -W -n app.orchard.tv/.MainActivity`, and test again.
2. `home_from_app` passed but took about 5 s: normal. Android handles the `input keyevent` Home itself and holds back app starts for 5 s after it; the remote's Home key is caught by Home Guard and does not wait.
3. `guard` passed, `home_from_app` failed: ask the user to press Home on the real remote, then after 8 s run `adb shell "dumpsys window | grep mCurrentFocus"`. `input keyevent` skips Home Guard's key filter; the remote is what counts.
4. Still not back: keep report.sh's "Home" and "Bound services" sections and `adb logcat -d -t 300 | grep -E "AerialGuard|ActivityTaskManager|ActivityManager"` after the key press for the maintainer.

### Standby (`standby` fails)

- The TV does not wake with Home Guard on and must be unplugged: try `CONFIG --ez guard_off_in_standby true` (#371); set it back to `false` if it does not help.
- Xiaomi loses ADB after standby: `CONFIG --ez keep_adb true`.
- Wakes on the old launcher: look again after 60 s (low-memory Xiaomi TVs come back through a sync service in 30-60 s). Still the old launcher: send the `standby` line with report.sh.

### Restart (`reboot` fails)

The self-test logs what was in front each second after boot. The old launcher first and Aerial a few seconds later is normal; the old launcher after a minute means the firmware only accepts its own launcher (see https://github.com/pengchujin/aerial-tv/tree/main/docs/devices ). Do not disable it; send the timeline.

### Brightness slider does nothing

Read `adb shell settings get global picture_backlight`, move the slider, read again. The number changes but the picture does not: add `"ignoresPictureBacklight": true` to the device profile. If it reads `null` (no such setting), do the same with `adb shell settings get system screen_brightness`; the number changes but the picture does not: add `"ignoresScreenBrightness": true`.

## Step 4: send it in

Write `aerial-adapt-<model>.md` and show it to the user first:

1. Brand and model, and what is wrong in the user's words.
2. The self-test before, and after your changes.
3. Each switch tried and its result (helped, or did not and was set back).
4. The final device profile JSON, if changed.
5. All of `aerial-report-<model>.md`.

Once the user agrees:

- With `gh` signed in (`gh auth status`):
  ```
  gh issue create -R pengchujin/aerial-tv --title "[机型] <model>" --body-file aerial-adapt-<model>.md
  ```
- Without it, open this address (fill in and URL-encode the values); GitHub fills in the form, and the user pastes the file into Diagnostics:
  ```
  https://github.com/pengchujin/aerial-tv/issues/new?template=device.yml&title=[机型] <model>&model=<model>&version=Aerial <version>&profile=<JSON>
  ```
