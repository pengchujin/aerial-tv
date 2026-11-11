# Performance validation and side-by-side preview

Historical benchmark report for the isolated preview. The later user-authorized daily migration
and HD icon release are documented in [DAILY-FORK.md](DAILY-FORK.md).

The `preview` flavor is for comparing this fork on a TV that already has official Aerial installed.
It has package `app.orchard.tv.preview`, label **Aerial Preview**, and phone configuration port 8791.
It declares no HOME activity, boot receiver, accessibility guardian, notification revival listener,
or sync/account services. Upstream update checks are disabled. HOME continues to open the installed
launcher. Preview has its own settings, photos and icon layout; it does not migrate upstream data.

## Build

Use JDK 17 and Android SDK platform 36 / build-tools 35.0.0. Set `JAVA_HOME`, and set `sdk.dir` in
ignored `local.properties` (or `ANDROID_HOME`).

```sh
./gradlew :app:assemblePreviewRelease :app:testPreviewDebugUnitTest
./gradlew :app:lintPreviewRelease
```

Only release builds are suitable for timing. Keep the local signing key: future preview APKs need
that same key for an in-place update. Do not uninstall official Aerial to work around its signature.

```sh
adb -s "$TV_SERIAL" install -r app/build/outputs/apk/preview/release/app-preview-release.apk
adb -s "$TV_SERIAL" shell cmd package compile -m speed -f app.orchard.tv.preview
adb -s "$TV_SERIAL" shell am start -W -n app.orchard.tv.preview/app.orchard.tv.MainActivity
```

Do not run the upstream `tools/install.sh` for preview: it is designed to download and configure the
official package. Preview deliberately does not replace the default desktop. Press HOME to return
to official Aerial. The original package, its data and permissions are independent.

## A/B procedure

1. Build preview from the instrumentation baseline commit recorded in the results, install it,
   let its wallpaper/assets finish loading, and verify its HOME screenshot before sending keys.
2. Run `python3 tools/benchmark-preview.py --serial "$TV_SERIAL" --out local/bench/baseline`.
3. Install the optimized preview **over the same package without clearing data**; keep the same
   wallpaper, tile arrangement, quality settings, refresh rate and compilation mode.
4. Run the same script into a new output directory. Compare scenario distributions, not just means.
5. Inspect screenshots, foreground checks, crash buffer, memory and idle captures. Test app launch/
   return, long-press, folder, and screensaver separately. Repeat measurements when conditions differ.

The script restarts only preview. It records 10 process-cold starts (disk/OS caches stay warm),
5 navigation runs, 5 Control Center runs, and a 10-second idle sample. It sends directional
keys inside panels, not confirmation on settings or HDMI sources. Stop if another app gains focus.

`TotalTime` is Android's launch metric, not network/ADB round-trip latency. The separate diagnostic
marker measures time after Compose content setup until the loaded home reaches its next frame; it
excludes initialization before content setup. `FrameCompleted - IntendedVsync` measures frame
completion latency; `GpuCompleted - SwapBuffers` is reported separately. The percentage above
16.67ms is a 60Hz budget comparison, **not** Android's own jank/deadline metric. Known AOSP special flag bits (0–3) and unfinished fences are excluded. This Xiaomi firmware
sets undocumented bit 16 on ordinary frames; it is retained and counted explicitly. Raw-timestamp
percentiles are therefore diagnostic, not a claim of platform-conformant frame flags. The report
also keeps Android’s own global histogram/deadline counters (not duplicated per-window counters). The ring buffer is finite, so individual runs are kept short.

Raw files include device properties and screenshots and belong in ignored `local/`. Publish only
reviewed aggregate results and non-sensitive screenshots. Do not compare the installed official
app's lifetime counters with controlled preview scenarios and claim an improvement.

## Design decisions

- Preserve the 960×540dp canvas, existing font licensing and tvOS-inspired hierarchy.
- Keep focus crisp, critically damped and interruptible; keep the high-contrast option.
- Defer animated state reads to draw/layer phases and rasterize focused faces off the UI thread.
- Collapse Control Center's two black scrims into one; dim its static snapshot in the image pass.
- Retain the existing shader fallback for older GPUs; avoid introducing real-time full-screen blur.
- Remove redundant application scans, retain locale/style/size invalidation, and avoid HDMI/
  brightness queries on volume broadcasts.

References: [Apple tvOS design](https://developer.apple.com/design/human-interface-guidelines/designing-for-tvos/),
[Compose performance](https://developer.android.com/develop/ui/compose/performance/bestpractices).

Frame flag reference: [Android 14 FrameInfo.h](https://github.com/aosp-mirror/platform_frameworks_base/blob/android14-release/libs/hwui/FrameInfo.h). Vendor bit 16 has no definition there.

## Validation environment (2026-10-24)

- Xiaomi MiTV-MFTR0, Android 14 / API 34, 1920×1080, Skia OpenGL; same 20 apps, wallpaper and preview data.
- Instrumented baseline: `bdde185`; candidate: `aed7ab2` (app sources). Both optimized release, compiled with `speed`.
- 24 Android unit tests passed, including preview isolation and locale cache invalidation; 4 Python parser tests passed.
- Both preview and standard release APKs built. Standard APK was not installed because it cannot update the upstream-signed package.
- Lint: candidate 10 errors / 93 warnings; compared every issue ID, severity and message to baseline. No new issue. Baseline has 11 errors, including the preview label fixed by localized resources. Remaining errors are upstream RestrictedApi and Media3 UnstableApi annotations. Full lint therefore does **not** pass.
- Raw captures stay in ignored `local/bench/`; artifact in `local/artifacts/`. The preview uses this computer’s existing signing key fallback, which must be retained for future updates.
- The 120-frame framestats ring truncates longer navigation sequences; keep raw percentiles separate from Android’s full-scenario counters. One baseline GPU histogram reports a 4950ms overflow bin, so do not use GPU histograms as precise GPU runtime.

## Primary A/B results

10 process-cold launches and 5 rounds per interaction scenario, in baseline → candidate order.
These are one-device observations, not statistical confidence bounds.

| Metric | Baseline | Candidate | Observation |
|---|---:|---:|---|
| Android TotalTime median | 607 ms | 598.5 ms | 1.4% lower |
| Loaded-frame marker median, after content setup | 545.5 ms | 532.5 ms | 2.4% lower; 20% target unmet |
| Navigation raw P50 / P95 / P99 | 15.30 / 35.10 / 64.29 ms | 15.51 / 33.98 / 36.06 ms | Typical frame unchanged; lower tail in this run |
| Navigation Android deadline jank | 26/925 (2.81%) | 18/984 (1.83%) | Full-scenario counters |
| Control Center raw P50 / P95 / P99 | 18.74 / 88.82 / 156.26 ms | 16.94 / 61.50 / 110.44 ms | Median −9.6%, P95 −30.8% |
| Control Center Android deadline jank | 86/298 (28.86%) | 69/344 (20.06%) | Still above smoothness target |
| PSS after panel scenario | 133386 KiB | 125657 KiB | Single snapshots, −5.8%; not a leak test |
| Frames during 10 seconds idle | 0 | 2 | No continuous animation; occasional status redraw |

The 60Hz P95 ≤16.7ms target remains unmet. Panel P95 is not end-to-end panel-opening latency,
so the separate first-open latency target is not proven by this measurement. Navigation’s
per-run P95 varies substantially; the lower P99 must not be advertised as a guaranteed reduction.
No long-duration memory-leak soak has been performed.

Full aggregate distributions and per-round platform counters: [baseline](benchmarks/2026-10-24-baseline.json),
[candidate](benchmarks/2026-10-24-candidate.json). Raw screenshots, activity diagnostics and frame tables
are retained locally, not published.

### Reverse-order confirmation

Reinstalled the baseline over the same preview data after the candidate, then ran 3 starts and
3 rounds per scenario. [Confirmation data](benchmarks/2026-10-24-baseline-confirm.json):

- Baseline launch median 596ms, so **no robust startup improvement** is established.
- Baseline navigation P50/P95/P99: 17.03/33.82/38.09ms; candidate P95 is effectively the same.
  The large initial navigation P99 improvement is not reproduced against the warmed baseline.
- Baseline Control Center P50/P95/P99: 18.65/71.80/110.95ms. Candidate median is 9.2% lower,
  P95 14.4% lower, P99 effectively unchanged. Android deadline jank: baseline 49/184 (26.63%),
  candidate 69/344 (20.06%). The consistent gain is panel frame cost, not elimination of first-open stalls.
- Retain navigation changes for fewer synchronous allocations, cache correctness and interruption
  behavior; do not claim guaranteed navigation throughput gains. No change introduced a material
  typical-frame regression in this comparison.

This is A/B/A confirmation, not randomized benchmarking; system load and warm caches remain
factors. The final installed preview was restored to the candidate APK afterward.

## Functional acceptance

Verified on the final candidate release, with screenshots and activity diagnostics retained locally:

- D-pad navigation, high-contrast focus, boundary nudges, Control Center open/navigation/close.
- Launched the TV weather app from its tile; Back returned to preview with the same weather tile focused.
- Held OK opened the app menu without triggering its first action on key-up.
- Created a temporary folder in preview, opened it, returned, and dissolved that same folder; original layout restored.
- Started the photo screensaver through the existing preview action; OK dismissed it without opening the focused app.
- Pressed HOME at the end: official `app.orchard.tv.MainActivity` is foreground again. The firmware HOME resolver
  remains the Xiaomi launcher and the existing official HomeGuard handles redirection.
- Accessibility-service list and system screensaver component matched their before captures exactly; ADB enabled remained `1`.
- No Aerial crash found in the captured crash buffer and no ANR observed during these runs. This is not a long-duration soak.

Not tested in this pass: reboot/standby, video screensaver/4K decoder transitions, reduced-motion system setting,
other TV models, or migration of official data/default-home ownership to the fork. Preview intentionally
does not take ownership of HOME or boot. Existing official boot behavior was not changed.

APK SHA-256: `6b0db4819d871f8c1f1cd27e0625e4680b7ad57a943fc40316e427ec5c6ac2c5`.
To try it, open **Aerial 预览版** from the installed-app list. HOME returns to official Aerial.
To remove the preview later, uninstall only `app.orchard.tv.preview`; original Aerial is independent.
