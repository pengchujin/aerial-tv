# Driving Aerial without the remote

For tests, scripts and AI agents: read what Home shows, change settings, open screens and focus
tiles through one broadcast, instead of pressing keys on the remote one at a time.

```sh
TV=<tv-ip> tools/agent.sh state
adb shell am broadcast -a app.orchard.tv.CONFIG -n app.orchard.tv/.guard.ConfigReceiver --es agent state
```

The answer is the broadcast's result data (`data="..."`); `tools/agent.sh` prints just that.

## Who may call it

The broadcast goes to `ConfigReceiver`, which the manifest guards with `WRITE_SECURE_SETTINGS`: the
ADB shell holds it and ordinary apps cannot get it, so no other app on the TV can send these. The
interface adds no new entry point and no new permission.

What it leaves out on purpose: the parental PIN, the adb access of App Info and what the TV boots
into. An agent that could set those could undo the owner's guards. Opening the hidden apps goes
through the PIN pad when a PIN is set, as for the owner.

## Commands

| `--es agent` | Needs | Answer |
|---|---|---|
| `help` | | one line listing the commands |
| `state` | | JSON: `overlay`, `focus` (the focused tile's key), `tiles` (key and label, in order), `settings` (key to value) |
| `settings` | | JSON array: each setting's `key`, `kind` (bool, int, choice), `value`, and its `options` or `range` |
| `set` | `--es key K --es value V` | `ok`, or `error: ...` saying what the setting takes (nothing is changed on an error) |
| `open` | `--es target T` | `ok`; T is `none`, `control_center`, `personalize` or `hidden_apps` |
| `focus` | `--es target KEY` | `ok`, or an error when there is no such tile; closes an overlay first |

`overlay` in `state` is one of `none`, `control_center`, `personalize`, `hidden_apps`, `app_menu`,
`folder`, `pin`. Bools take `true`/`false` or `on`/`off`.

Settings: `one_row_home`, `peek_next_row`, `home_columns`, `home_plate`, `show_all_names`,
`clock_right`, `large_clock`, `show_weather`, `focus_style`, `wallpaper_always_moves`, `screensaver`,
`aerial_quality`, `screensaver_clock`, `screensaver_minutes`, `back_starts_screensaver`, `ui_sounds`,
`effects`. `settings` is the current list with each one's values.

## Adding a setting

Add a line to `AgentSettings.all()` (`agent/AgentSettings.kt`): `AgentSetting.bool`, `.int` or
`.choice` with the setting's getter and its `choose...` function. The parsing, the checking of a
value and the JSON are in `AgentApi` and covered by `AgentApiTest`.
