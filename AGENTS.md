# 🤖 AGENTS.md

Guidance for AI agents and human contributors working on **Warden**. Read this before changing code. For how the engine works, read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## 📌 Project at a glance

- **What:** Android app that genuinely disables Developer options, USB and wireless debugging, and accessibility services (all by default, or a chosen set) while a user-selected app is in the foreground, then restores the exact previous state.
- **applicationId:** `dev.abdulsamad.warden` (the code namespace and Kotlin packages stay `app.warden`). Changing the applicationId makes Android treat it as a different app: installs stop upgrading in place, saved settings are lost and the adb grant must be run again. Ask first.
- **Stack:** Kotlin 2.0.21, Jetpack Compose + Material 3, AndroidX Lifecycle/ViewModel, DataStore Preferences, coroutines/Flow, Shizuku API 13.1.5 (optional grant only, AIDL enabled for it).
- **SDK:** minSdk 26, compileSdk/targetSdk 35, JDK 17, AGP 8.7.3, Gradle 8.10.2.
- **Shape:** one `:app` module, one process. Engine in `app.warden`, UI in `app.warden.ui`.
- **Website:** `site/` is the Astro landing page at https://warden.abdulsamad.dev, deployed by Cloudflare Pages on pushes to `main` that touch `site/`. It is separate from the Gradle build.

## 🛠️ Commands

```bash
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # install to user 0 only (see installation block in app/build.gradle.kts)
./gradlew assembleRelease      # R8 + shrinkResources; needs the release key (fails without it)
scripts/release.sh 1.2.3 --dry-run   # build + verify a release locally, publish nothing
./gradlew lint                 # Android lint
```

One-time device grant (required for the engine to do anything):

```bash
adb shell pm grant dev.abdulsamad.warden android.permission.WRITE_SECURE_SETTINGS
```

Or, with no computer on Android 11+, **Grant with Shizuku** in Setup. To test the grant step again, `adb shell pm revoke dev.abdulsamad.warden android.permission.WRITE_SECURE_SETTINGS`.

> [!NOTE]
> There are **no unit or instrumented tests** yet. The in-app **Test on this device** self-test is the on-device check. If you add tests, put them under `app/src/test` / `app/src/androidTest` and update this file.

## 🧠 Core invariants (do not break)

These protect the user's device from being left with settings switched off. Every change to the engine must keep all of them.

1. ✍️ **`SettingsController` is the only writer of secure settings.** No other class calls `Settings.Global.put*` / `Settings.Secure.put*`.
2. ✅ **Every write is verified by read-back.** A write that reads back unchanged is a failure, never a success. Do not "simplify" this away - OEMs (Samsung `adb_enabled`) silently revert writes.
3. 🔒 **Every state transition holds `SettingsController.lock`.** That covers service `evaluate`/`enter`/`leave`, `Recovery.restorePending` and `SelfTest.run`. New code that changes managed settings must take the same lock.
4. 💾 **Persist the snapshot before disabling anything.** `store.saveSnapshot(...)` comes before `applyDisabled(...)`. This ordering is what makes crash recovery possible.
5. 🛡️ **Restores run in `NonCancellable`.** Cancellation must never strand a disabled setting.
6. ⛔ **A `null` snapshot field means "not managed - do not touch".** Restore must leave unmanaged settings alone.
7. 🧯 **Any unexpected error during enter triggers an immediate `leave()`.** Rethrow `CancellationException` after restoring.
8. 🎛️ **Config is the single source of truth for pause and targets.** The notification, tile and UI write `ConfigStore`; the service reacts by collecting it. Do not add parallel pause state. "Until screen off" is the `PAUSE_UNTIL_SCREEN_OFF` sentinel in `pausedUntil`, ended by `endScreenOffPause()` on screen off and on every service start.
9. 📡 **`MonitorStatus` is in-process only.** No IPC, no persistence. Process death resetting it to `Stopped` is correct.
10. 📜 **Logging never blocks or breaks a transition.** Wrap `ActivityLog` calls in `runCatching`. Write the log only at transitions, never per poll.
11. 🚫 **Nothing is spoofed.** Warden changes real device state. Do not add hooks, fake values, or hiding of settings from other apps.
12. 🧯 **Every DataStore has a corruption handler.** A corrupt `config` is replaced with defaults plus a reset marker that `Recovery.reportConfigReset` turns into an alert. Never drop that marker: it is the only signal that a pending restore was lost.
13. 🔁 **Resume work runs in `MainActivity.onResume()`**, which is where cold-start Recovery happens. Don't move it back into a Compose lifecycle observer.
14. 🔐 **No network, no backups.** Do not add `INTERNET`. Keep `allowBackup="false"` and `data_extraction_rules.xml` - a snapshot restored on another device would re-enable stale settings there.
15. 📲 **Shizuku is only for the one-time grant.** `ShizukuGrant` / `GrantUserService` run `pm grant` for `WRITE_SECURE_SETTINGS` and nothing else, with argv (never a shell string). The engine must never depend on Shizuku being installed or running. Keep the `GrantUserService` rule in `proguard-rules.pro`: Shizuku instantiates it by class name.
16. ⏱️ **The launch hold is short-lived and in memory only.** A Protected launch widget tap makes `evaluate` treat a not-yet-visible app as in front. The hold must end on that app's first foreground event newer than the tap, after `LAUNCH_HOLD_MS` on the monotonic clock, or on screen off. Never persist it, lengthen it, or time it on the wall clock, or settings could stay off while the user is somewhere else. `ProtectedLaunchActivity` never writes settings; it only asks the service.
17. 🔕 **The status notification is dismissible; failure alerts never share its channel.** It is deliberately not `setOngoing`: on Android 13+ the user can swipe it away while the service keeps running, and state changes then stop re-posting it for the rest of that run (`notificationDismissed`). **Hide** opens the `monitor` channel's settings. Don't move failure alerts onto that channel: hiding or swiping away the status must never silence an alert.

## 🔋 Performance rules

The service runs 24/7, so:

- Keep the reused `UsageEvents.Event`, change detection, and the idle behavior: the 750 ms poll runs only while the screen is on and something is watched (apps chosen and not paused, or a widget launch in progress); otherwise a 5 s heartbeat, which wakes in time for a pause to end and polls before evaluating.
- Do not read DataStore inside the poll loop. The active snapshot and config live in memory.
- Only re-post the ongoing notification when `MonitorState` changes.
- Keep UI-only state (like `selfTestSuggested`) **out of `AppConfig`**, so changing it does not wake the service.
- UI polling (the grant step's 1 s re-check) must be scoped to the screen being visible and resumed, and stop when it is no longer needed.

## 🎨 UI conventions

- **Frozen and contract surfaces.** `ui/Components.kt` is FROZEN for feature work - define variants privately in the section file instead. `SetupSection(vm, modifier)`, `TargetsSection(vm, showPicker, onShowPickerChange, modifier)` and `SetupState` / `requiredComplete` / `refresh` are marked `CONTRACT` and must keep their names and meanings. Picker visibility (apps and accessibility services) is owned by `MainScreen`, so "Finish setup" items can open pickers directly. The adb command box is `AdbGrantCommand()` and the Shizuku steps are `ShizukuSteps`, both shared by Setup and the guide; don't duplicate them.
- **Strings:** never hard-code user-facing text. Put it in `res/values/strings_<feature>.xml` (`main`, `setup`, `targets`, `engine`, `guide`, `widget`); only truly shared strings go in `strings.xml`. Use plurals where counts appear.
- **Motion:** curves and durations live only in `ui/Motion.kt`; screens use the specs in `ui/Components.kt` (`motionTween` for reveals and crossfades, `modalTween` for dialogs and overlays, `exitTween` for anything leaving, `morphTween` for on-screen size changes, `colorSpec` for colors) and `Modifier.pressScale` for press feedback. Never inline a raw `tween`/`spring` value. Use `AnimatedDialog` for dialogs so they animate out as well as in, and have buttons inside call its `dismiss`. No overshoot on ordinary UI, keep UI motion under 300 ms, honor `LocalReducedMotion`, and keep native motion on switches, checkboxes and the bottom sheet.
- **Accessibility:** pass a `description` to `StatusDot` whenever color alone conveys state; mark purely decorative icons with a null content description; keep tap targets at least 48 dp (`SetupButtonModifier`).
- **UX rules:** a one-tap action that removes something gets an Undo (`offerUndo`), not a confirmation dialog; subtitles must never contradict the control they sit under; Back must always close overlays before leaving the app; haptics (`rememberHaptics`) only for commits and verdicts; every "Finish setup" item must be tappable and lead straight to its fix; content stays within `ContentMaxWidth`; rows of side-by-side buttons stack at large font scales. Keep section titles direct and consistent with the status text ("Apps to watch" / "Watching 1 app").
- **Settings deep links** go in `ui/SetupIntents.kt` via `launchFirst(...)`, with fallbacks, because OEM builds drop screens.
- **Version checks:** gate APIs with `Build.VERSION.SDK_INT` (minSdk is 26). Existing gates cover notifications (33), tile subtitle (29), background restriction (28), unused-app pause (30), the Shizuku option (30), `startActivityAndCollapse(PendingIntent)` (34).
- **Guide copy:** keep it short. One idea per line, no line longer than two sentences; details belong in Setup, next to the control they describe.

## 🌐 Website (`site/`)

```bash
cd site && pnpm install
pnpm dev                       # http://localhost:4321
pnpm build && pnpm preview     # production build; the CSP only applies here, not in dev
```

- One static page, no framework, no web fonts. One small script: copy buttons, the hero demo, the scroll reveal, and the latest release (version, size, SHA-256) from `api.github.com`, the one origin the CSP allows. Without it the page still works. Links use `releases/latest/download/warden.apk`, so a release needs no site change.
- Motion uses the tokens at the top of `global.css` (`--ease-out`, `--ease-in-out`, `--press`, `--enter`, `--exit`), the same rules as the app: `scale(0.97)` on press, `transform`/`opacity` only, exits faster than entrances, nothing over 300ms on UI, hover effects only inside `@media (hover: hover) and (pointer: fine)`, and a reduced-motion variant that keeps fades and drops movement. The hero demo plays once (under 5 seconds), then waits for a tap; state changes use transitions, not keyframes, so they reverse cleanly mid-way.
- Keep the copy in line with the README and the app: claim only what Warden does. Don't name phone makers (say "some phone makers", "your phone") or specific third-party apps; the tools Warden works with (Shizuku, Obtainium) are fine.
- The CSP is a `<meta>` tag Astro generates with hashes (`security.csp` in `astro.config.mjs`); `public/_headers` adds what a `<meta>` CSP can't (`frame-ancestors`). Don't add inline `style` attributes or third-party scripts.
- Cloudflare Pages deploys the site from `main`, only when a commit touches `site/`: root directory `site`, build command `pnpm run build`, output `dist`. The build's Node and pnpm versions are set in the hosting settings, so a change to `site/.node-version` or `packageManager` in `site/package.json` needs the maintainer to update them too. There is no Wrangler config, because the site has no Functions or bindings for one to describe.
- `public/og.jpg` (1200x630) and `docs/brand/warden-social-preview.png` (1280x640, for GitHub's social preview) are rendered from the icon; redo them if the icon or tagline changes.

## 🎨 Brand assets

The icon geometry is defined in `res/values/paths.xml` and `res/drawable/ic_launcher_*.xml`. `docs/brand/warden-icon.svg` mirrors them. **Edit the resources first**, then update the SVG and brand sheet.

## ✍️ Writing style

- Kotlin official code style (`kotlin.code.style=official`), 4-space indent, trailing commas.
- KDoc explains **why** (invariants, OEM quirks), not what the code obviously does. Keep existing comments accurate when you change behavior.
- ASCII punctuation only in code, comments and docs: `'`, `"` and `-`. No em dashes or smart quotes.

## 🚦 Ask before you

- ➕ Add, remove or upgrade a dependency (versions live in `gradle/libs.versions.toml`, and in `site/package.json` for the website).
- 🔐 Add or remove a manifest permission, or change the foreground service type.
- 🔑 Change signing, `applicationId`, or the `--user 0` install option. Never read, print, move or commit anything in `~/.android/keystores/warden/` (the release keystore and `signing.env`).
- 🚢 Publish a release (`scripts/release.sh` without `--dry-run`), push a tag, or delete a release. See [docs/RELEASING.md](docs/RELEASING.md).
- 🧠 Change any core invariant above, the lock scope, or the snapshot format (`ConfigStore` keys are persisted on real devices - changing them needs a migration).
- 📦 Commit or push.

## ✅ Before you finish

1. `./gradlew assembleDebug` builds cleanly (and `lint` for UI or manifest changes).
2. For engine changes: install the **release** build over the release build (`adb install -r`, same key, keeps the grant), run **Test on this device**, open and leave a target app, and confirm the settings come back. Also simulate a hard kill mid-session and confirm Recovery restores on next open. Turn off the USB debugging protection first (otherwise adb drops while the target is open), then run `adb shell am force-stop dev.abdulsamad.warden` with a target in front.
3. For Setup or Shizuku changes: revoke the grant (see Commands), check the grant step, then grant it back.
4. Update [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), the README and this file if you changed behavior, invariants, or commands, and add user-visible changes to `CHANGELOG.md` under the next version.
