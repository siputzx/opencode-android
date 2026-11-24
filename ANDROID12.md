# Android 12 fork (unofficial)

This is an unofficial fork of [anomalyco/opencode-android](https://github.com/anomalyco/opencode-android)
for running the app on Android 12 (API 31) and newer.

- Base: `d442dea` on `dev` (21 Nov 2026).
- Not affiliated with Anomaly. The upstream repository has no license file; this fork is
  published for personal/testing use. Use at your own risk.
- No pull request is opened against upstream.

## Changes

- `minSdk` lowered from 33 to 31, with API 33 features guarded or replaced on Android 12:
  `RuntimeShader`/AGSL, per-app language (`LocaleManager`), on-device speech, predictive back,
  `VibrationAttributes`, notification permission, and a few JDK APIs.
- Local "run finished" notifications, because the open-source build has no Firebase
  configuration and the Android push relay is not available:
  - `LocalSessionNotifier` posts Done / Failed / Needs your input on the Session Updates channel
    (high importance, session title, reply preview, tap opens the session).
  - `SessionRunService` is a quiet foreground service that keeps the process and server stream
    alive while a run started from this phone is working.
- Visual fallbacks on Android 12: the activity orb is drawn with CPU dots; AGSL backgrounds and
  glows are skipped.
- Lint `:app` + `:feature:system` passes with no errors.

## Build

Needs JDK 17 and Android SDK platform 37 / build-tools 37.

```sh
./gradlew :app:assembleDebug
```

The APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

The release APK is debug-signed, package `ai.opencode.mobilecode.debug`.

```sh
adb install -r opencode-android-android12.apk
```

If an official debug build with a different signature is installed, uninstall it first.

## Limitations

- Firebase/FCM push and the relay are not available; notifications are local and work while the
  process is alive (helped by the foreground service).
- Per-app language (Android 13+) is not available; Settings › Language only offers the system
  default.
- AGSL shader effects (onboarding, drawer, connection EKG, message glow, voice orb) are skipped
  on Android 12.
- Dictation uses the system recognizer instead of the Android 13 on-device model.
- Debug build: larger and slower than a minified release build.
