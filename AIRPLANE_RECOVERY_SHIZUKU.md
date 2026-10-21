# Airplane Recovery (Shizuku) – Clash Meta for Android

Auto airplane mode recovery when proxy connections stay failed, using **Shizuku only** (no root).

## Features

- **Health watch** – periodic `Clash.healthCheckAll()`; recovery when no proxy has `0 < delay < 10000`
- **Shizuku toggle** – `cmd connectivity airplane-mode` (API 30+) or settings + broadcast
- **Auto permission request** – enabling the switch requests Shizuku permission or opens the Shizuku app
- **Status indicator** – shows Granted / Denied / Not running / Not installed; tap to fix
- **Notifications** – user-visible alerts when recovery starts, finishes, fails, or Shizuku is missing
- **UI** – integrated into **Settings → Network** using the same preference screen style as the rest of Clash Meta

## Settings → Network layout

1. Tips (HTML) explaining the feature  
2. **Shizuku status** (clickable) – live indicator + deep link to Shizuku  
3. **Auto airplane recovery** switch – auto-requests permission when turned on  
4. **Failure threshold** – 3 / 5 / 8 / 10 / 15 / 20 s  
5. **Cooldown after toggle** – 1–60 seconds (1 / 2 / 3 / 5 / 10 / 15 / 20 / 30 / 45 / 60 s)  

Threshold and cooldown stay disabled until Shizuku is granted and recovery is enabled.

## Files

| Path | Role |
|------|------|
| `service/.../util/ShizukuShell.kt` | Shell + status + open Shizuku |
| `service/.../module/AirplaneRecoveryModule.kt` | Watchdog + notifications |
| `service/.../TunService.kt` / `ClashService.kt` | Install module |
| `service/.../store/ServiceStore.kt` | Preferences |
| `design/.../NetworkSettingsDesign.kt` | UI |
| `app/.../NetworkSettingsActivity.kt` | Permission flow + open Shizuku |
| `app` / `service` manifests & Gradle | Shizuku provider & deps |

## Usage

1. Install and start **Shizuku** (Wireless debugging or ADB).  
2. Build and install this project.  
3. **Settings → Network → Airplane Recovery**  
4. Tap **Shizuku status** if not authorized (or just enable the switch).  
5. Grant permission in the Shizuku dialog / app.  
6. Enable **Auto airplane recovery**.  
7. Start Clash; recovery runs in the `:background` process.

## Build

```bash
./gradlew :app:assembleMeta-AlphaRelease   # adjust flavor as needed
```

Maven Central must resolve `dev.rikka.shizuku:api` and `provider` (13.1.5).

## Build note
minSdk raised to **24** (Android 7.0) because Shizuku `api`/`provider` 13.1.5 declare minSdk 24.
A lower app minSdk fails `:app:processMetaReleaseMainManifest` with
"uses-sdk:minSdkVersion 23 cannot be smaller than version 24 declared in library [dev.rikka.shizuku:api:13.1.5]".

## Shizuku in the `:background` process
Shizuku only delivers its binder to `ShizukuProvider`, which lives in the main process, but the
watchdog runs in `:background`. `MainApplication` calls
`ShizukuProvider.enableMultiProcessSupport()` in every process, and `ShizukuShell.ensureBinder()`
pulls the binder into `:background` (throttled, capped retries). Without this the settings
screen showed "Authorized" while the watchdog reported Shizuku as not ready.

## Build note: ARM64 only
`ndk.abiFilters`, the CMake `abiFilters` and the Go core are restricted to `arm64-v8a`. There is
no `splits.abi` block (it conflicts with `abiFilters` once the universal APK is off), so a single
APK is produced: `app/build/outputs/apk/meta/release/*.apk`.

## Shizuku+ support
Authorization targets **Shizuku+** (`af.shizuku.plus.api`, thejaustin/ShizukuPlus); stock Shizuku
(`moe.shizuku.privileged.api`) is only a fallback. `ShizukuShell.managerPackage()` picks Shizuku+
first. The manifest requests `af.shizuku.plus.permission.API_V23` (plus the stock `moe.*` one) and
declares `af.shizuku.client.V3_SUPPORT`/`moe.shizuku.client.V3_SUPPORT` so the app is listed in
Shizuku+ "Authorized apps". The binder protocol is unchanged, so the rikka `api`/`provider`
13.1.5 libraries keep working.
