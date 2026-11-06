# 🚢 Releasing Warden

Releases are built and signed on the maintainer's machine and published to [GitHub Releases](https://github.com/abdulsamad/warden/releases) by `scripts/release.sh`. Obtainium users get them automatically.

## 🔑 Signing key (one-time)

The release key is **not** in this repository.

| What | Where |
|---|---|
| Keystore (PKCS12, RSA 4096, valid to 2056, alias `warden`) | `~/.android/keystores/warden/release.keystore` |
| Passwords and path | `~/.android/keystores/warden/signing.env` (read by `app/build.gradle.kts`) |
| Public certificate SHA-256 | README "Install" section and `EXPECTED_CERT_SHA256` in `scripts/release.sh` |

Like any other project's signing key, it lives in its own folder under `~/.android/keystores/`:

```
~/.android/keystores/warden/
├── release.keystore
└── signing.env
```

`signing.env` holds:

```properties
WARDEN_RELEASE_KEYSTORE=/Users/<you>/.android/keystores/warden/release.keystore
WARDEN_RELEASE_STORE_PASSWORD=...
WARDEN_RELEASE_KEY_ALIAS=warden
WARDEN_RELEASE_KEY_PASSWORD=...
```

> [!CAUTION]
> **Back up the whole `~/.android/keystores/warden/` folder off this machine** (a password manager or encrypted backup). If the key is lost, no future APK can install over an existing Warden: every user would have to uninstall, lose their settings and redo the adb grant. Never commit the key; `.gitignore` excludes `*.jks`, `*.keystore`, `*.p12`, `keystore.properties` and `signing.env`.

Without this file, `assembleRelease` fails on purpose rather than producing an unsigned or debug-signed APK.

## 🔢 Versioning

- `VERSION_NAME` in `gradle.properties` is the only place the version is set (semver `MAJOR.MINOR.PATCH`, each part 0-99).
- `versionCode` is derived from it: `1.2.3` becomes `10203`, so it always increases with the version.
- Tags are `v<version>`. Asset names carry **no version** (`warden.apk`), so `https://github.com/abdulsamad/warden/releases/latest/download/warden.apk` always serves the newest release. The README and the website link to that URL: never rename the asset.

## 📦 Cutting a release

1. Move the notes from `## [Unreleased]` in `CHANGELOG.md` into a new `## [x.y.z] - YYYY-MM-DD` section.
2. Set `VERSION_NAME=x.y.z` in `gradle.properties`.
3. Commit, push `main`.
4. Optionally check the build first, with no tag and no upload:

   ```bash
   scripts/release.sh x.y.z --dry-run
   ```

5. Install `dist/warden.apk` on a phone and run through the app: setup, **Test on this device**, open and leave a watched app. R8 only runs on release builds, so the debug build doesn't prove this.
6. Publish:

   ```bash
   scripts/release.sh x.y.z
   ```

The script stops at the first problem. Before publishing it checks that:
- `gh` is installed and logged in
- you're on a clean `main` that matches `origin`
- the tag (locally and on `origin`) and the GitHub release don't exist yet
- the version is higher than the last `v*` tag (Android won't install a lower version over a higher one)
- `VERSION_NAME` and the changelog section match the version

It then runs `lintRelease` and `assembleRelease`, checks the APK is v2-signed **with the expected certificate**, and asks for confirmation. Finally it tags, pushes the tag, and creates the release with these assets:

| Asset | Purpose |
|---|---|
| `warden.apk` | The app. The only APK, so Obtainium needs no filter. |
| `warden.apk.sha256` | Checksum users can verify. Also at a fixed URL, `releases/latest/download/warden.apk.sha256`, for anything that shows the current checksum. |
| `warden-mapping.txt` | R8 mapping, to read stack traces from user reports. Each release keeps its own copy; `dist/` only holds the latest. |

The release notes are that version's changelog section, plus a link to the one-time setup, the checksum and the certificate fingerprint. Only the title and notes can be edited afterwards.

Releases are **immutable** (repository setting): once published, the assets can't be replaced and the tag can't be moved or deleted. GitHub also signs a release attestation, so anyone can check a download is the published file:

```bash
gh release verify-asset vX.Y.Z warden.apk
```

## 🧯 If something goes wrong

- **Wrong release published:** its files and tag are locked, so don't try to fix it in place. Release a **new patch version** with the fix, and edit the bad release's notes to say which version replaces it. Never reuse a version number: installed copies and Obtainium have already seen it.
- **Tag pushed, but creating the release failed** (network or `gh` error): only a published release is locked. If nothing shows on the Releases page, nobody has seen the version, so it's safe to reuse. Remove the tag with `git push --delete origin vX.Y.Z && git tag -d vX.Y.Z`, plus any half-made draft with `gh release delete vX.Y.Z`, fix the cause and run the script again. If the release did get published, treat it as **Wrong release published** above.
- **Script refuses the certificate:** the build picked up a different key. Check `~/.android/keystores/warden/signing.env` before doing anything else.
