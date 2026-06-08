# Zenify 🌿

**Calm your apps. Save your battery.**

Zenify is a modern, root-free app-hibernation tool for Android — a reimagining of
[Greenify](https://play.google.com/store/apps/details?id=com.oasisfeng.greenify) built with
**Kotlin + Jetpack Compose (Material 3)**. It puts misbehaving apps to sleep so they stop
draining your battery, holding RAM, and waking your device in the background.

---

## ✨ Features

- **Root-free hibernation** with two interchangeable engines:
  - **Shizuku** — silent & instant. Force-stops apps the moment they go idle. Required for
    background auto-hibernation, and unlocks the rich process-state labels below.
  - **Accessibility** — no root, no PC. Zenify opens each app's *App info* screen and taps
    **Force stop → OK** for you, exactly like Greenify's non-root mode.
- **Greenify-style live status labels** (with Shizuku): *In use*, *Running as foreground*,
  *Working*, *Background-free (cached)*, *Being used by input method*, *Hibernated*.
- **Running apps grouped first** with **long-press multi-select** + select-all.
- **Smart safety**: the default *Hibernate all* skips risky apps (active keyboard, launcher,
  accessibility services, device admins). You can still force them by selecting explicitly.
- **Auto-hibernation**: a foreground watcher hibernates idle apps on a timer and the instant
  the screen turns off (Shizuku only). Re-arms after reboot.
- **Pull-to-refresh**, search, per-app whitelist, light/dark + dynamic Material You theming.

---

## 🚀 Quick start

Requirements: **JDK 17+**, **Android SDK** (compileSdk 35 / build-tools 35+), and a connected
device or emulator (`adb devices`). The Gradle wrapper (8.14) is included — no global Gradle
needed.

Use the helper script (PowerShell, Windows):

```powershell
./zenify.ps1 run               # build + install + launch (debug)
./zenify.ps1 dev               # watch sources; rebuild + reinstall + relaunch on save
./zenify.ps1 restart           # relaunch without rebuilding
./zenify.ps1 debug             # assemble debug APK
./zenify.ps1 install-debug     # build + install debug
./zenify.ps1 release           # assemble release APK
./zenify.ps1 install-release   # build + install release
./zenify.ps1 logcat            # tail app logs
./zenify.ps1 uninstall         # remove debug & release
./zenify.ps1 help
```

> **About "hot reload":** native Android/Compose has no command-line hot reload (Live Edit is
> an Android Studio feature). `./zenify.ps1 dev` is the practical equivalent — it watches
> `app/src` and runs a fast **incremental rebuild + reinstall + relaunch** every time you save.

Prefer raw Gradle?

```bash
./gradlew :app:assembleDebug      # or installDebug / assembleRelease / installRelease
```

---

## 🔐 Setting up an engine

Open **Settings → Hibernation engine** in the app. Neither engine is required just to browse.

- **Accessibility (no root):** tap *Enable accessibility* and turn on "Zenify hibernation".
  Works on any device, runs on demand (you'll briefly see the system screens).
- **Shizuku (silent):** install [Shizuku](https://shizuku.rikka.app/), start it via Android 11+
  *Wireless debugging* or from a PC, then return to Zenify and grant access. Needed for
  background auto-hibernation and the detailed status labels.

---

## 🏗️ Architecture

```
app/src/main/java/com/pn/zenify/
├── ZenifyApp.kt                 Application + notification channel
├── data/
│   ├── AppInfo.kt               App model + RunState (foreground/fg-service/working/cached/stopped)
│   ├── AppRepository.kt         Discovery + Greenify-style classification (PackageManager,
│   │                            UsageStatsManager, process importance, risk detection)
│   └── ZenPrefs.kt              DataStore settings, managed/whitelist sets
├── core/
│   ├── HibernationEngine.kt     Picks Shizuku → Accessibility → none
│   ├── Hibernator.kt            Eligibility + batch force-stop (Shizuku)
│   ├── HibernationTracker.kt    Marks force-stopped apps so the list updates instantly
│   └── AccessibilityUtil.kt     Detect/open the accessibility service
├── shizuku/
│   ├── IUserService.aidl        Privileged service contract
│   ├── UserService.kt           Runs `am force-stop` + reads process importance (shell uid)
│   └── ShizukuManager.kt        Permission + user-service binding
├── accessibility/
│   ├── ForceStopController.kt   Queue + per-package state machine
│   └── ForceStopService.kt      Taps "Force stop" + confirm dialog
├── service/
│   ├── HibernationService.kt    Foreground auto-hibernation watcher (screen-off + timer)
│   └── BootReceiver.kt          Re-arms the watcher after reboot
└── ui/                          Compose Material 3 screens, ViewModel, theme, components
```

**Why native (not Flutter):** every meaningful capability — Shizuku AIDL, `am force-stop`,
`ActivityManager` process importance, `UsageStatsManager`, foreground services, accessibility
automation — is a native Android system API. Flutter would mean writing all of that in a Kotlin
plugin anyway, plus a bridge tax on the icon-heavy app list. Native removes the bridge entirely.

---

## 📦 Release signing

For convenience the **release** build is signed with the debug key so `installRelease` works
locally. For a real Play Store release, create a keystore and replace the `signingConfig` in
[`app/build.gradle.kts`](app/build.gradle.kts) with your own.

---

## ⚠️ Notes & limits

- Process-importance labels (cached / foreground-service / working) require **Shizuku** — they
  rely on privileged `ActivityManager` data the OS hides from ordinary apps. Without it, Zenify
  falls back to a usage-stats heuristic.
- Hibernating (force-stopping) an app cancels its alarms/jobs until you next open it — that's the
  point. Don't hibernate apps you rely on for background delivery unless you accept delayed
  notifications.
- If you also have Greenify's accessibility service enabled, disable it while testing Zenify so
  the two don't both try to automate the same *Force stop* screens.
