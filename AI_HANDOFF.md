# MiniRepeater: handoff notes for an AI assistant

Paste this file together with the project zip when asking an AI to change the app.
The family-wide rules are in STANDARD.md (ask the owner to attach it if missing).

## What this is
Root-powered Wi-Fi Direct Repeater for Android 10+ (designed for rooted LineageOS 19.1 devices like Samsung Galaxy Tab A6 SM-T585). Broadcasts a 2.4 GHz Wi-Fi Direct network with automatic kernel NAT (iptables + ip rule) and automatic DHCP (dnsmasq) so clients connect with just a password and zero proxy.
Android app, Kotlin + Jetpack Compose Material3. Source package `com.khereba1.app`, applicationId `com.khereba1.minirepeater`.
minSdk 29, compileSdk 35, targetSdk 34. UI languages: Arabic & English. Developer name: Khereba1.
Uses Wi-Fi / Location / Foreground Service / Boot / Internet permissions (required for Wi-Fi Direct Repeater + root NAT/DHCP).

## How it is built (IMPORTANT)
The owner has only a phone. There is NO local build. Code is pushed from Termux to GitHub and GitHub Actions
(.github/workflows/build.yml) builds the APK with Gradle 8.10.2 (AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01).
You cannot compile: be careful and conservative. A mistake shows up only as a failed Actions run.
- Avoid Kotlin JVM signature clashes (`var x` with `private set` plus `fun setX(...)` breaks the build).
- Every new string needs an entry in every language file the app has (values/strings.xml and values-ar/strings.xml).
- Do not bump library versions without checking that the exact version exists.

## Files (app/src/main/java/com/khereba1/app)
- `App.kt` — Application class, `AppLog` file/crash logger, `LocaleHelper` (system/ar/en), `Prefs` helper.
- `RepeaterState.kt` — `ServiceStatus`, `UiState`, and `RepeaterState` StateFlow for live UI updates.
- `RootRouter.kt` — Root shell (`su -c`) helpers for `ip addr`, `ip rule` (pref 90/100), `iptables` NAT/FORWARD, `/system/bin/dnsmasq` DHCP, client count, and `pmf=0` patcher.
- `RepeaterService.kt` — Persistent Foreground Service (`connectedDevice`) managing `WifiP2pManager.createGroup` (2.4 GHz), `WakeLock`, `WifiLock`, and 8s watchdog.
- `BootReceiver.kt` — Optional auto-start on `BOOT_COMPLETED`.
- `MainActivity.kt` — Jetpack Compose Material 3 UI (Minimal Dark Premium, System/Dark/Light theme, System/AR/EN language, Start/Stop, SSID/Password, Auto-start, Patch PMF, live log).

## REQUIRED answer format (the owner has only a phone + Termux)
1. Briefly say what you changed and why (plain language; the owner writes Egyptian Arabic mixed with English terms, reply in the same).
2. Deliver ONE zip containing only the changed/new files, complete (never snippets or diffs), with paths relative to the project root.
   Name it `fixN.zip` (fix1, fix2, ...). If you cannot create a zip, give each file in full in its own code block with its path, and say so.
3. Raise `versionCode` (+1) and `versionName` in `app/build.gradle.kts` for every functional change, and include that file.
4. Give the install command, ready to copy, with the real folder name:
   `cd ~/minirepeater && unzip -o "$(ls -t ~/storage/downloads/fixN*.zip | head -1)" -d . && git add . && git commit -m "short message" && git push`
   (GitHub repo: Khereba1/MiniRepeater, APK: github.com/Khereba1/MiniRepeater/releases/tag/latest)
5. Say honestly that nothing was compiled or tested, and what to check first on the phone.
6. If the build fails, the owner sends lines containing `e: ` from the Actions log; fix only those.

## Signing and sharing
The stable signing key lives in GitHub Secrets (KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD).
Never print, commit or ask for them, and never ask for the backup folder.
`bash share_zip.sh` builds a clean `<folder>-for-AI.zip` (no keys, env files, build output or APKs) for sharing.
