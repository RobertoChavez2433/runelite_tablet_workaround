# RuneLite for Tablet — MVP Implementation Plan

**Date**: 2026-02-22
**Status**: Approved
**Approach**: Vertical Slices — end-to-end working app first, then add features slice by slice

## Overview

Break the MVP into 5 vertical slices, each producing a more complete app. Slice 1 gets RuneLite on screen with minimal code. Each subsequent slice adds one component with full UI and error handling.

**Reference**: Design doc at `.claude/plans/2026-02-21-runelite-tablet-design.md`

---

## Slice 1 — "It launches" (End-to-end proof)

Get RuneLite on screen with the minimum possible code.

| # | Task | What it does |
|---|------|-------------|
| 1.1 | Scaffold Android project | Kotlin, Jetpack Compose, Material3, min SDK 26, single-activity |
| 1.2 | Create Termux integration layer | `RUN_COMMAND` intent to execute shell commands in Termux |
| 1.3 | Write setup shell script | Single bash script: `proot-distro install ubuntu`, `apt install openjdk-11-jdk`, download RuneLite .jar |
| 1.4 | Write launch shell script | Single bash script: `proot-distro login ubuntu` → `DISPLAY=:0 java -jar runelite.jar` |
| 1.5 | Minimal UI: two buttons | "Setup" button runs the setup script, "Launch" button runs the launch script |
| 1.6 | End-to-end test on tablet | Install APK → tap Setup → tap Launch → RuneLite appears |

**Exit criteria**: RuneLite is running on the tablet via the app. Ugly, hardcoded, no error handling — but it works.

---

## Slice 2 — "Setup is smooth" (Setup Wizard)

Replace the hardcoded setup with a proper guided experience.

| # | Task | What it does |
|---|------|-------------|
| 2.1 | Break setup into discrete steps | Separate scripts: install Termux, install proot/Ubuntu, install Java, download RuneLite, setup X11/audio |
| 2.2 | Build Setup Wizard UI | Stepper component: progress bar, step name, status text per step |
| 2.3 | Add step execution engine | Run each step, capture stdout/stderr, detect success/failure |
| 2.4 | Add retry + resume | Each step is idempotent. "Retry" re-runs the failed step. Resume picks up where it left off. |
| 2.5 | Persist setup state | Config file tracking which steps are complete |

**Exit criteria**: First-run setup is guided, shows progress, handles failures gracefully, and remembers completion.

---

## Slice 3 — "Auth works" (Auth Manager)

Add credential management so you actually log in.

| # | Task | What it does |
|---|------|-------------|
| 3.1 | Build credential import UI | Screen with instructions + text field or file picker for `credentials.properties` |
| 3.2 | Parse credentials | Extract `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME` from file |
| 3.3 | Secure storage | Store credentials in Android Keystore (encrypted) via EncryptedSharedPreferences |
| 3.4 | Inject into launch | Read stored creds → pass as env vars to proot launch script |
| 3.5 | Handle missing/invalid creds | Prompt re-import if creds are missing or auth fails |

**Exit criteria**: Import credentials once and launch RuneLite with your account.

---

## Slice 4 — "Stays up to date" (Update Manager)

Auto-update RuneLite so you always have the latest version.

| # | Task | What it does |
|---|------|-------------|
| 4.1 | Check RuneLite releases API | Hit GitHub releases API, parse latest version |
| 4.2 | Compare versions | Store current version locally, compare with latest |
| 4.3 | Download + replace | Download new .jar, swap in place of old one |
| 4.4 | Integrate into launch flow | Check for updates before launching (non-blocking if offline) |

**Exit criteria**: App auto-updates RuneLite .jar on each launch.

---

## Slice 5 — "It's an app" (Polish)

Main screen, settings, and error handling that make it feel complete.

| # | Task | What it does |
|---|------|-------------|
| 5.1 | Main screen | Central Launch button, status indicators (environment health, version, last login) |
| 5.2 | Settings screen | Auth management, advanced options (launch flags, display settings) |
| 5.3 | Error handling pass | Launch failure logs, scrollable log view, "Copy Log" button |
| 5.4 | Health checks | Verify Termux/proot/Java are intact before launch, offer fix-up if broken |

**Exit criteria**: Full MVP — a polished app that sets up, authenticates, updates, and launches RuneLite.

---

## Technical Architecture

### Termux Communication

Uses `RUN_COMMAND` intent — Termux's official API for receiving commands from external apps. Requires user to enable `allow-external-apps` in Termux settings (one-time toggle).

### Project Structure

```
runelite-tablet/
├── app/
│   ├── src/main/
│   │   ├── java/com/runelitetablet/
│   │   │   ├── MainActivity.kt
│   │   │   ├── ui/
│   │   │   │   ├── screens/        # Compose screens (Setup, Launch, Auth, Settings)
│   │   │   │   ├── components/     # Reusable UI components
│   │   │   │   └── theme/          # Material3 theme
│   │   │   ├── setup/              # Setup wizard logic
│   │   │   ├── auth/               # Credential parsing & secure storage
│   │   │   ├── launch/             # Launch manager
│   │   │   ├── update/             # Update checker
│   │   │   └── termux/             # Termux intent/command layer
│   │   ├── assets/
│   │   │   └── scripts/            # Shell scripts bundled with APK
│   │   └── res/
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle/
```

### Shell Scripts (bundled in `assets/scripts/`)

Scripts are bundled with the APK and sent to Termux via intent. Keeps logic readable, testable independently, and modifiable without recompiling.

- `setup-environment.sh` — Installs proot-distro, Ubuntu, Java, RuneLite
- `launch-runelite.sh` — Enters proot, sets env vars, launches RuneLite
- `check-update.sh` — Checks GitHub API, downloads new .jar if needed
- `health-check.sh` — Verifies all components are installed and working

### Key Libraries

| Library | Purpose |
|---------|---------|
| Jetpack Compose + Material3 | UI |
| AndroidX Security (EncryptedSharedPreferences) | Credential storage |
| Ktor Client or OkHttp | GitHub API calls for update checking |
| Jetpack Navigation | Screen navigation |
| Jetpack DataStore | App preferences & setup state |

---

## Prerequisites

### Development Environment

| Requirement | Details |
|-------------|---------|
| Android Studio | Latest stable (Ladybug or newer), with Kotlin 2.0+ |
| Physical device | Samsung Tab S10 Ultra — no emulator can test Termux/proot |
| Termux | F-Droid build (Play Store build is outdated/broken) |
| Termux:X11 | Companion app for display output |
| ADB | For deploying debug APKs to tablet |
| Min SDK | API 26 (Android 8.0) |
| Target SDK | API 35 (Android 15) |

### External Dependencies (orchestrated, not shipped)

- **Termux** — side-loaded from F-Droid
- **Termux:X11** — side-loaded
- **proot-distro** — Termux package (`pkg install proot-distro`)
- **RuneLite .jar** — downloaded from GitHub releases at setup time

---

## Risk Register

| Risk | Impact | Mitigation |
|------|--------|------------|
| proot too slow for RuneLite | App works but game unplayable | Software rendering is 50fps (proven by meteor-mobile); proot overhead is mostly syscalls not rendering. Fallback: chroot with root. |
| Termux `RUN_COMMAND` intent unreliable | Can't execute scripts | Fall back to `~/.termux/boot/` or guide user to run manually |
| RuneLite .jar doesn't run on ARM64 OpenJDK 11 | Crashes on launch | .jar is architecture-independent; JVM handles it. RuneLite ships ARM64 Linux builds. |
| Jagex changes auth flow | Credentials stop working | `JX_SESSION_ID` doesn't expire — low risk for MVP. OAuth2 in Phase 2. |
| Termux/proot-distro project dies | Core dependency gone | Both actively maintained open-source. Worst case: fork. |
| Samsung restricts background processes | Termux killed mid-session | Guide user to disable battery optimization for Termux |
