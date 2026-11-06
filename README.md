<div align="center">

<img src="docs/brand/warden-icon.svg" alt="Warden app icon" width="128" height="128" />

# Warden

**Switches off Developer options, USB and wireless debugging, and accessibility services while picky apps are open - and puts everything back the moment you leave.**

![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?logo=android&logoColor=white)
![Target SDK](https://img.shields.io/badge/targetSdk-35-2F4DD8)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?logo=jetpackcompose&logoColor=white)
![No internet](https://img.shields.io/badge/network-none-131D5E)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-blue.svg)](LICENSE)
[![Latest release](https://img.shields.io/github/v/release/abdulsamad/warden?color=2F4DD8)](https://github.com/abdulsamad/warden/releases/latest)

</div>

---

## 🛡️ What it does

Some apps (banking, payment and wallet apps, trading apps, government and ID apps, a few games) refuse to run when Developer options, USB debugging or an accessibility app is enabled. Turning those off by hand every time, and back on afterwards, gets old fast.

Warden does it for you:

1. You pick the **apps to watch** and **what to switch off**.
2. When a watched app comes to the front, Warden **really switches those settings off**. Nothing is spoofed or hidden - the app sees a clean phone because it is one.
3. When you leave the app (or the screen turns off), Warden **restores the exact previous state**: the same accessibility services, in the same order, and anything that was already off stays off.

## ✨ Features

| | Feature | Details |
|---|---|---|
| 🎯 | **Per-app watching** | Choose any launchable app from a searchable picker with icons. |
| 🧭 | **Guided setup** | A numbered checklist with phone-specific tips. Every unfinished step is one tap from its fix, and the self-test is suggested once you're done. |
| 📲 | **No computer needed** | On Android 11+, do the one-time grant on the phone through [Shizuku](https://shizuku.rikka.app/) instead of adb. |
| 🔧 | **Three protections** | USB and wireless debugging, Developer options, and accessibility services - all of them by default, or only the ones you pick. Toggle each independently. |
| ♻️ | **Exact restore** | The pre-session state is snapshotted and put back verbatim, verified by read-back. |
| 💥 | **Crash-safe** | The snapshot is saved *before* anything is disabled. If Warden is killed or the phone reboots mid-session, it restores on the next start. |
| ⏸️ | **Pause** | For 15 minutes, an hour, or until the screen turns off. **Pause 1 hour** is one tap on the notification. |
| 🧩 | **Quick Settings tile** | Turn the monitor on or off from the shade; long-press opens the app. |
| 🚀 | **Protected launch widget** | For apps that check the instant they open: the widget switches settings off first, then opens the app. Add one from **Apps to watch** in one tap. See [below](#-apps-that-check-the-moment-they-open). |
| 🧪 | **On-device self-test** | Flips each managed setting off, verifies it, and restores it, so you know your OEM build cooperates. |
| 📜 | **Activity log** | The last 20 protected sessions, including anything that failed to disable or restore. |
| ↩️ | **Undo** | Removing a watched app or clearing the log can be undone from the snackbar. |
| 🙈 | **Hideable status notification** | Android requires one while Warden runs. Tap **Hide** on it and turn off **Monitor**; Warden keeps working and failure alerts still come through. |
| 🔔 | **Failure alerts** | A separate notification channel only fires when a setting could not be switched off or put back. |
| 🔋 | **Light on battery** | Polls every 750 ms only while the screen is on; idles at 5 s with the screen off. No accessibility service of its own. |
| ♿ | **Comfortable to use** | Large text, TalkBack, tablets and foldables, and the system "remove animations" setting are all supported. Light and dark follow the system, and dark is true black for OLED. |
| 🔒 | **Private by design** | No `INTERNET` permission, and app data is excluded from cloud backup and device-to-device transfer. |

## 📋 Requirements

- 📱 Android **8.0 (API 26)** or newer
- 💻 For the one-time permission grant: a computer with **adb**, or on Android 11+ the [Shizuku](https://shizuku.rikka.app/) app (no computer needed)

## 📥 Install

1. Download [`warden.apk`](https://github.com/abdulsamad/warden/releases/latest/download/warden.apk) (always the [latest release](https://github.com/abdulsamad/warden/releases/latest)) and open it on your phone.
2. Do the [one-time setup](#-one-time-setup) below.

**Auto-updates with [Obtainium](https://github.com/ImranR98/Obtainium):** in Obtainium tap **Add app** and paste `https://github.com/abdulsamad/warden`, or open [this link on your phone](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/abdulsamad/warden). Each release has exactly one APK, so Obtainium picks it up without any filters.

**Check the download is genuine.** Every release lists the APK's SHA-256. Releases are signed with this certificate:

```
SHA-256: 96:D1:02:37:48:EA:FD:44:1A:E6:CE:88:72:AA:D9:79:30:6A:37:B8:56:4A:39:6F:08:C4:3C:E8:DB:41:06:A4
```

You can check it with `apksigner verify --print-certs warden.apk`. Android itself also refuses to update Warden with an APK signed by a different key.

> [!NOTE]
> Google Play Protect may warn about a sideloaded app that asks for `WRITE_SECURE_SETTINGS` and `QUERY_ALL_PACKAGES`. That is expected for Warden; it has no internet access at all.

## 🚀 Build from source

Needs **JDK 17** and the Android SDK (API 35).

```bash
# Debug build, installed into the main user profile only
./gradlew installDebug
```

Release builds are signed with the project's release key, which is not in this repo; see [docs/RELEASING.md](docs/RELEASING.md). A debug build and a release build can't be installed over each other because they're signed with different keys. Uninstall one first, then redo the one-time grant.

> [!TIP]
> Installs are pinned to `--user 0`. On Samsung, adb would otherwise also install into the Dual Messenger profile (user 95).

## 🔑 One-time setup

Warden walks you through this in its **Setup** card, and **Help** (the **?** in the top bar) covers the rest. The short version:

1. **Grant secure-settings access** once, from a computer with adb over USB or Wireless debugging (Setup has a Copy button for this):

   ```bash
   adb shell pm grant dev.abdulsamad.warden android.permission.WRITE_SECURE_SETTINGS
   ```

   **Rather skip the computer?** On Android 11+, [Shizuku](https://shizuku.rikka.app/) does the same grant on the phone:

   1. Install Shizuku.
   2. Turn on **Wireless debugging** in Developer options. Wi-Fi must be on.
   3. In Shizuku, tap **Pairing** and enter the code from **Wireless debugging > Pair device with pairing code**. Then tap **Start**.
   4. Return to Warden, tap **Grant with Shizuku**, then **Allow**.

   You can uninstall Shizuku afterward: the grant stays.

   Either way the grant survives reboots and updates. It is only lost if you uninstall Warden.

2. **Allow Usage access** so Warden can tell which app is in front.
3. **Keep Warden running** - exempt it from battery optimization and, on Android 11+, turn off "Pause app activity if unused".
4. **Allow notifications** (recommended) so you see status and failure alerts.
5. Pick your **apps to watch**, and check **what to switch off**. Accessibility defaults to **All services**; tap **Choose services** to limit it to specific ones.
6. Tap **Start monitor**, then **Test** when Warden suggests it (or **Test on this device** any time). Three "Works" results means you are done.

> [!IMPORTANT]
> Some OEMs silently revert `adb_enabled`; Warden detects that by reading every write back and tells you instead of pretending it worked. Run **Test on this device** once on every new phone.

### 📱 Phone-specific steps

Setup detects the phone maker and shows these automatically:

| Phone | If the adb grant fails | To keep Warden running |
|---|---|---|
| **Samsung** (One UI) | - | Battery: Unrestricted, add to **Never sleeping apps**, Keep open in Recents |
| **Xiaomi / Redmi / Poco** (MIUI, HyperOS) | Turn on **USB debugging (Security settings)** in Developer options | **Autostart** on, Battery saver: **No restrictions**, lock in Recents |
| **Oppo / Realme / OnePlus** (ColorOS, Realme UI, OxygenOS) | Turn on **Disable permission monitoring** in Developer options | Battery usage: **Allow background activity** and **Allow auto launch**, lock in Recents |
| **Vivo / iQOO** | - | Allow **high background power consumption**, Autostart if shown, lock in Recents |
| **Motorola, Pixel, near-stock** | - | The standard battery step is enough |

## 🔕 The status notification

Android requires a visible notification while Warden runs in the background. It is silent and shows what Warden is doing (**Watching**, **Protecting** or **Paused**), with **Pause 1 hour** (or **Resume**) and **Hide**.

Don't want it? Tap **Hide** and turn off the **Monitor (safe to turn off)** category on the screen that opens. Warden keeps working, and failure alerts still come through their own **Alerts** category, so keep that one on.

## 🚀 Apps that check the moment they open

Warden notices a watched app within about a second of it opening. Most apps check later than that, so opening them from their own icon just works.

A few apps check **the instant they start** (Mumbai One is one). They beat that second, see a setting still on (Developer options, USB debugging or an accessibility app), and refuse to run, even though Warden switches everything off a moment later. Reopening doesn't help: they win the race every time.

> [!IMPORTANT]
> For those apps, open them from Warden's **Protected launch** widget instead of their own icon. The widget switches the settings off **first**, waits for Warden to confirm, **then** opens the app, so its very first check sees a clean phone.

1. Make sure the app is under **Apps to watch** and the monitor is on.
2. In Warden, under **Apps to watch**, tap **Add home screen widget** (pick the app if you watch several), then confirm in your launcher's dialog.
3. If the app is open, close it fully (swipe it away in Recents): the widget can't restart an app that's already running. From now on, always open it from that Warden widget, not its own icon from the drawer. The widget shows the app's icon with a small Warden badge.

Launcher doesn't offer to add it? Long-press your home screen, tap **Widgets**, find **Warden**, drag **Protected launch** onto the home screen, and pick the app. Only watched apps are listed.

Leaving the app restores everything exactly as usual. If Warden can't protect the launch (monitor off, paused, or the app isn't watched), the widget says why and opens the app as its icon would.

## 🩺 Not working?

Check these in order:

1. **Is the monitor on?** The top card should say **Watching**. If it says **Monitor off**, tap **Start monitor**. Setup alone doesn't start it.
2. **Is the app listed?** It must be under **Apps to watch**.
3. **App still complains?** It checks the instant it opens, before Warden can react. Add a [Warden widget](#-apps-that-check-the-moment-they-open) for it under **Apps to watch**, close the app fully, then always open it from that widget, not its own icon from the drawer.
4. **Accessibility:** if you picked specific services, switch back to **All services**.
5. **Run Test on this device** again, especially after a big Android update.
6. **Root checks:** Warden can't help with apps that also look for root or a modified system.
7. **Other blocked apps:** some apps also refuse to run while certain apps are installed, Shizuku among them. Once Warden has its permission you can uninstall Shizuku; the grant stays.

## 💡 Pro tips

- **Quick adb session?** Pause **until the screen turns off**. It ends by itself, so you can't forget to resume.
- **Quick Settings tile:** add it to turn the monitor on or off from the shade. Long-press it to open the app.
- **Hide the notification:** tap **Hide** on it and turn off **Monitor (safe to turn off)**. Warden keeps working.
- **Lock Warden in Recents** so Clear all never stops it.
- **New phone or big update?** Run **Test on this device** once.

## 🔐 Permissions

| Permission | Why |
|---|---|
| `WRITE_SECURE_SETTINGS` | Actually flip Developer options, USB and wireless debugging, and accessibility services. Granted once via adb or Shizuku. |
| `moe.shizuku.manager.permission.API_V23` | Added by the Shizuku library. Lets Warden ask Shizuku to run the one-time grant; unused otherwise. |
| `PACKAGE_USAGE_STATS` | Detect the foreground app without running an accessibility service. |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the monitor alive with an ongoing notification (which you can hide). |
| `RECEIVE_BOOT_COMPLETED` | Restore leftovers and restart the monitor after a reboot or update. |
| `POST_NOTIFICATIONS` | Status notification and failure alerts (Android 13+). |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask not to be killed by aggressive battery managers. |
| `QUERY_ALL_PACKAGES` | List installed apps for the app picker. |

## 🗂️ Project layout

```
warden/
├── app/src/main/java/app/warden/
│   ├── ForegroundMonitorService.kt   # 🧠 the engine: poll, enter, leave
│   ├── SettingsController.kt         # ✍️ the only secure-settings writer
│   ├── ConfigStore.kt                # 💾 DataStore config + crash-durable snapshot
│   ├── Recovery.kt                   # 🩹 restores a leftover snapshot
│   ├── MonitorStatus.kt              # 📡 live in-process state
│   ├── SelfTest.kt                   # 🧪 on-device verification
│   ├── ActivityLog.kt                # 📜 bounded session history
│   ├── EngineNotifications.kt        # 🔔 channels, text, alerts
│   ├── WardenTileService.kt          # 🧩 Quick Settings tile
│   ├── LaunchWidgets.kt              # 🚀 Protected launch widget
│   ├── ProtectedLaunchActivity.kt    #    switches settings off, then opens the app
│   ├── BootReceiver.kt               # 🔁 boot / update hook
│   ├── ShizukuGrant.kt               # 📲 optional no-computer grant
│   ├── GrantUserService.kt           #    runs `pm grant` inside Shizuku
│   └── ui/                           # 🎨 Compose screens and view models
├── app/src/main/aidl/                # IGrantService, the one Shizuku call
├── app/src/main/res/                 # strings_<feature>.xml, icons, themes
├── scripts/release.sh                # 📦 build, verify and publish a release
├── CHANGELOG.md                      # 📝 release notes
└── docs/
    ├── ARCHITECTURE.md               # 🏗️ how the engine works
    ├── RELEASING.md                  # 🚢 signing and release process
    └── brand/                        # 🎨 icon SVG and brand sheet
```

## 📚 Documentation

- 🏗️ [Architecture](docs/ARCHITECTURE.md) - components, state machine, session lifecycle, crash recovery, concurrency
- 🤖 [AGENTS.md](AGENTS.md) - conventions and invariants for anyone (human or AI) changing the code
- 🚢 [Releasing](docs/RELEASING.md) - signing key, versioning and publishing a release
- 📝 [Changelog](CHANGELOG.md)

## 🛠️ Tech stack

Kotlin 2.0, Jetpack Compose (Material 3, dynamic color on Android 12+), AndroidX Lifecycle / ViewModel, DataStore Preferences, Kotlin coroutines and Flow, Shizuku API (optional grant), AGP 8.7, Gradle 8.10.

## 📄 License

[GPL-3.0-or-later](LICENSE). Copyright (C) 2026 abdulsamad.

Use it, study it, change it and share it. If you share a changed version, share its source under the same license.
