# Contributing to Warden

Thanks for helping. Warden changes real settings on real phones, so the bar is: never leave a setting switched off by mistake.

## Before you start

- **Read [AGENTS.md](../AGENTS.md).** It lists the core invariants every change must keep, and the things to ask about first.
- **Open an issue first** for anything bigger than a small fix, and for any new dependency or permission. It saves you work if the idea doesn't fit.
- How the engine works is in [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).

## Building

Needs JDK 17 and the Android SDK (API 35).

```bash
./gradlew assembleDebug   # debug APK
./gradlew installDebug    # install on a connected phone
./gradlew lint
```

Then grant the one-time permission:

```bash
adb shell pm grant dev.abdulsamad.warden android.permission.WRITE_SECURE_SETTINGS
```

The website lives in `site/` (Astro, pnpm): `cd site && pnpm install && pnpm dev`.

## Pull requests

- One change per pull request, with a title that says what it does.
- Test on a phone: run **Test on this device**, then open and leave a watched app and check every setting comes back.
- Update the README, `docs/ARCHITECTURE.md` or `AGENTS.md` if behavior changes, and add user-visible changes to `CHANGELOG.md` under `## [Unreleased]`.
- Code style: Kotlin official style, ASCII punctuation in code, comments and docs.

## License

By contributing, you agree that your work is released under the project's [GPL-3.0-or-later](../LICENSE) license.
