# Security policy

Warden changes secure Android settings, so security problems matter more than usual. Thank you for reporting them privately.

## Supported versions

Only the [latest release](https://github.com/abdulsamad/warden/releases/latest) gets fixes. Fixes ship as a new patch release.

## Reporting a problem

**Don't open a public issue.** Report it privately instead:

1. Open [Report a vulnerability](https://github.com/abdulsamad/warden/security/advisories/new) on this repository.
2. Describe the problem, the Warden and Android versions, and the steps to reproduce it.
3. If you have a proof of concept, attach it there.

Reports are answered as soon as possible. Once a fix is released, the advisory is published with credit to you, unless you'd rather stay anonymous.

## What counts

- Anything that could leave a setting switched off after Warden should have restored it.
- Another app using Warden to change settings, or abusing its `WRITE_SECURE_SETTINGS` grant.
- Problems with how releases are built, signed or published.

An app that still detects Developer options or an accessibility service while Warden is running is not a security problem. Please open a regular [bug report](https://github.com/abdulsamad/warden/issues/new/choose) for that.

## Checking a release

Every release is signed with the same key. Its certificate SHA-256 is:

```
96:D1:02:37:48:EA:FD:44:1A:E6:CE:88:72:AA:D9:79:30:6A:37:B8:56:4A:39:6F:08:C4:3C:E8:DB:41:06:A4
```

Check it with `apksigner verify --print-certs warden.apk`, or check a download against the release with `gh release verify-asset <tag> warden.apk`.
