# TinyFox personal build

TinyFox is a personal fork of [Mika](https://github.com/wangzan101/Mika).
It has a separate Android identity and signing key. It does not provide a guarantee
against any third-party application's account restrictions or detection.

## Stable identity

- App: `TinyFox`
- Application ID: `io.github.smartjoytech.tinyfox`
- Accessibility service: `io.github.smartjoytech.tinyfox.a11y.TinyFoxAccessibilityService`
- Update/feedback repository: `SmartJoy-Tech/Mika`
- Upstream baseline: `tinyfox/upstream.json`
- Version: upstream version + `-tinyfox.<revision>`; versionCode = upstream versionCode * 100 + revision.

The internal Kotlin namespaces, Binder interfaces, sandbox paths and upstream
attribution stay compatible with Mika. This is a maintained rebrand, not an
attempt to remove every reference to the upstream implementation. Android string
resources are branded at build time, so new translations are picked up on the
next build. The accessibility entry class, recovery code, state checks and computer
CLI point at TinyFox; the underlying phone-control implementation follows upstream.

## SmartJoy icon

The personal build uses the owner-provided SmartJoy symbol without Chinese or English lettering. `tinyfox/res` supplies the launcher artwork, a white adaptive-icon background, a monochrome launcher layer and the notification silhouette. The same symbol is used in the welcome/about screens and capsule/drawer entry points. The UI tile stays white for contrast in both themes. `tinyfox/branding.json` records the asset fingerprint. These resources override the upstream artwork at build time so upstream icon updates do not replace the personal branding.

## Automatic upstream sync

`tinyfox` is the product branch and the intended default branch. The original `main` branch is kept as an
upstream reference. `.github/workflows/tinyfox-sync.yml` checks the latest stable
upstream release every hour at minute 17 (GitHub schedules can be delayed).
The workflow must be on the default branch and Actions must be enabled.

1. Fetch the latest stable tag and validate that its history moves forward.
2. Merge into a candidate branch while retaining TinyFox modifications.
3. Run the source checks, Git regression tests, core/app JVM tests and unsigned APK build.
4. Verify the actual APK's package, service, version and launcher label.
5. Fast-forward `tinyfox` only if all checks passed and the base has not changed.

No changes means no candidate and no build. Conflicts, moved accepted tags,
upstream workflow/wrapper changes, and failed checks stop the workflow and leave
the current product branch intact. Review the failed run in Actions; no force
push or automatic conflict resolution is used. Unchanged results do not send
additional messages. GitHub's own Actions notification settings apply to failures.

`workflow_dispatch` permits a manual check. `repository_dispatch` with type
`upstream-release` permits an authenticated webhook integration later; no upstream
webhook is currently configured. The scheduled workflow does not require this laptop.
GitHub may disable scheduled workflows on a public repository after prolonged inactivity;
check Actions if the upstream has been quiet for a long period.

Build jobs use a read-only token, do not receive signing secrets, and disable
persisted checkout credentials. Only the prepare/promote jobs can update branches.
The candidate commit SHA is pinned between preparation, testing and promotion.

## Build and sign

Use JDK 17, Android SDK platform 36 and the checked-in Gradle wrapper:

```sh
python3 tinyfox/verify.py
python3 -m unittest discover -s tinyfox -p 'test_*.py' -v
./gradlew :core:test :app:testReleaseUnitTest :app:assembleRelease
```

On Windows use an ASCII-only build path and LF line endings. Run the full test suite on Linux; filesystem security tests require POSIX semantics. The arm64 artifact is
`app/build/outputs/apk/release/app-arm64-v8a-release-unsigned.apk`.
CI artifacts are unsigned build results, not installable phone updates.

`tinyfox/sign-local.ps1` signs an APK with a permanent local key. Pass `-UnsignedApk`,
`-OutputApk`, `-JavaHome`, `-BuildTools`, and use `-Initialize` only for the first
signing. The key stays in `%LOCALAPPDATA%\TinyFox\signing\release.p12` outside this
repository and cloud workspace. Its password is encrypted with Windows DPAPI.
Only the public certificate fingerprint belongs in this repository. Back up the
key and arrange a recoverable password backup before replacing the Windows profile;
copying the DPAPI file to a different account does not make it decryptable there.

Never upload a private keystore/password, model credential, nmem key, or GitHub
token to source control or build logs. Cloud sync/build does not upload this local key,
publish APK Releases, install on the phone, or change its permissions.

The in-app updater points at this fork and retains HTTPS URL, package, increasing
versionCode, digest and signer verification. It will offer an update only after a
signed TinyFox APK has been published as a stable Release in this fork. A display
version containing `-tinyfox.N` is deliberate; mark the GitHub Release stable.
Original Mika APKs are not accepted as TinyFox updates.

## First migration and acceptance

The personal release exposes the advanced preferences page so settings from an
upstream perf build can be copied, including vision grounding. This does not
enable Android debugging, profiling, detailed traces, or autonomous mode.

TinyFox installs as a separate app. Existing Mika data, Magpie credentials, Android
Keystore keys, wireless-debugging pairing, accessibility and other permissions do
not automatically transfer. Keep the original installation until a separately
planned migration and phone-level test are complete.

Before daily use, check launch, Magpie connection, phone control, stop/cancel,
restart/recovery and one TinyFox-to-TinyFox update retaining data. A successful
compile/JVM test alone does not prove those device-level checks.

## Upstream notices

Original Mika copyright: 2026 wangzan101, Apache-2.0. TinyFox modifications change
the identity, branding, update source, build/signing and synchronization workflow.
Keep `LICENSE`, `NOTICE` and `licenses/` intact. Bundled runtime/native components
have their own licenses. Before distributing APKs publicly, follow upstream's
`tools/sandbox/README.md` source-package instructions as applicable.
