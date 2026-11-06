# Changelog

All notable changes to Warden are listed here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/). Each release's notes on GitHub are taken from its section below.

## [Unreleased]

## [1.0.0] - 2026-11-07

First public release.

### Added

- Switches off USB and wireless debugging, Developer options and accessibility services while a watched app is in front, and restores the exact previous state when it leaves or the screen turns off.
- A permission prompt or share sheet that a watched app opens counts as part of that app, so settings don't come back on underneath it.
- Protected launch widget, for apps that check the instant they open (such as Mumbai One) and refuse to run even though Warden is watching them. The widget switches the settings off first, then opens the app. Leaving the app restores everything as usual. If Warden can't protect the launch, the widget says why and opens the app normally.
- Add home screen widget, under Apps to watch: places a Protected launch widget already set to the app, through your launcher's own dialog. Launchers that can't do this get the manual steps.
- Accessibility: all enabled services by default, or only the ones you pick.
- Crash-safe restore: the previous state is saved before anything is switched off and restored after a crash, force stop, reboot or update.
- On-device self-test that checks your phone accepts each change.
- Pause for 15 minutes, an hour, or until the screen turns off, and a Quick Settings tile. The notification pauses for an hour in one tap.
- No-computer setup on Android 11+: grant the one-time permission through Shizuku instead of adb.
- Recent activity log with Undo for clearing it, and Undo when removing a watched app.
- Guided setup with phone-specific steps for Samsung, Xiaomi / Redmi / Poco, Oppo / Realme / OnePlus and Vivo / iQOO. Every unfinished step is one tap from its fix, and the self-test is suggested once setup is done.
- In-app Help with a copyable adb command.
- Works with large text sizes, TalkBack, tablets and foldables, and follows the system "remove animations" setting live.
- Light and dark themes that follow the system setting. Dark is true black for OLED screens.
- Failure alerts when a setting could not be switched off or put back.
- Hide button on the status notification: turn off its Monitor category and Warden keeps running, with failure alerts still on.
- Recovers from a damaged settings or activity file instead of failing on every launch, and alerts you if a pending restore may have been lost.
- Light on battery: Warden only checks which app is in front while the screen is on and something is being watched.
