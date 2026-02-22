# RuneLite for Tablet — Design Document

**Date**: 2026-02-21
**Status**: Approved

## Overview

An Android installer app (Kotlin/Jetpack Compose) that automates the setup and launch of the real RuneLite client on a Samsung Tab S10 Ultra via Termux + proot-distro. This is not a clone or port — it runs the actual RuneLite .jar with all plugins.

**Target device**: Samsung Tab S10 Ultra (Snapdragon 8 Gen 3, 12-16GB RAM)
**Primary input**: Physical mouse + keyboard via Samsung DeX

## Architecture

```
Android App (Kotlin/Jetpack Compose)
  ├── Setup Wizard (one-time): Installs Termux, proot-distro, Ubuntu ARM64,
  │   OpenJDK 11, RuneLite .jar, PulseAudio, Termux:X11
  ├── Auth Manager
  │   ├── Phase 1 (MVP): Import credentials.properties from desktop
  │   └── Phase 2: Native Jagex OAuth2 via Android Trusted Web Activity
  ├── Launch Manager: Starts proot → sets JX_* env vars → launches RuneLite .jar
  │   → displays via Termux:X11
  ├── Update Manager: Checks/downloads latest RuneLite .jar on launch
  └── Input Layer
      ├── Primary: Physical mouse + keyboard (DeX)
      └── Fallback: Touch-as-trackpad overlay
```

### Key Technical Decisions

- RuneLite runs as `.jar` in proot (not AppImage — FUSE doesn't work in proot)
- Auth uses environment variables (`JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`)
- `JX_SESSION_ID` does not expire — authenticate once, play indefinitely
- RuneLite's `--insecure-write-credentials` flag enables credential caching
- Jagex OAuth2 supports Android Trusted Web Activity natively
- GPU acceleration (Phase 2): Mesa Zink (OpenGL 4.6 via Vulkan) + Turnip driver

## UX Flow

### First-Run Experience

1. **Open app** — Welcome screen: "RuneLite for Tablet — Run the real RuneLite on your tablet"
2. **Setup Wizard** (automated, ~5-10 min):
   - Step 1: "Installing Linux environment..." (Termux + proot-distro + Ubuntu ARM64)
   - Step 2: "Installing Java runtime..." (OpenJDK 11)
   - Step 3: "Downloading RuneLite..." (latest .jar from RuneLite CDN)
   - Step 4: "Setting up display & audio..." (Termux:X11 + PulseAudio)
   - Each step shows a progress bar + status text. On failure: clear error with a "Retry" button.
3. **Auth Setup**: "Import credentials" screen — user pastes or uploads `credentials.properties` from their desktop RuneLite. Shows instructions for locating the file.
4. **Done** — "Setup complete! Tap Launch to start RuneLite"

### Subsequent Launches

1. **Open app** — Main screen with a big "Launch RuneLite" button
2. **Update check** (~2-3 seconds): auto-downloads new .jar if available
3. **Launch**: proot → env vars → RuneLite → Termux:X11 takes over the screen
4. **Playing**: Full RuneLite experience with all plugins
5. **Exit**: Close RuneLite → returns to the Android app

### Main Screen Layout

- Big central "Launch" button (primary action)
- Status indicators: environment health, RuneLite version, last login
- Settings gear icon → auth management, display settings, advanced options

## Component Details

### Setup Wizard

- Checks if Termux is installed; if not, downloads the Termux APK (F-Droid build, not Play Store)
- Runs `proot-distro install ubuntu` inside Termux
- Inside proot Ubuntu: `apt install openjdk-11-jdk pulseaudio`
- Downloads RuneLite .jar from `https://github.com/runelite/launcher/releases`
- Installs Termux:X11 companion app if not present
- Stores state in a config file so it knows setup is complete

### Auth Manager

- **MVP**: Reads `credentials.properties` from user. Extracts `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`. Stores in Android Keystore (encrypted).
- **Phase 2**: Opens Jagex OAuth2 login via Android Trusted Web Activity. Exchanges auth code for tokens. Same storage.
- On each launch, reads stored credentials and passes as env vars to proot session.

### Launch Manager

- Starts Termux session programmatically
- Enters proot: `proot-distro login ubuntu`
- Sets env vars: `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `DISPLAY=:0`
- Starts PulseAudio server
- Launches: `java -jar runelite.jar --insecure-write-credentials`
- Hands display to Termux:X11

### Update Manager

- On launch, checks RuneLite's GitHub releases API for latest version
- Compares against locally stored version
- If newer: downloads new .jar, replaces old, updates stored version
- If offline or check fails: launches with existing .jar (non-blocking)

### Input Layer

- **DeX mode** (primary): Physical mouse + keyboard work through X11 passthrough
- **Touch fallback** (Phase 2+): Transparent overlay translating touch to mouse events (tap = click, drag = move, two-finger = right-click)

## Phasing Plan

### MVP (v0.1.0) — "It runs"

- Android app with setup wizard (Termux + proot + Ubuntu + Java + RuneLite)
- Credential import from desktop (copy `credentials.properties`)
- Launch Manager: starts proot, sets env vars, launches RuneLite via Termux:X11
- Update Manager: auto-downloads latest RuneLite .jar
- Software rendering only (~50fps)
- DeX input only (physical mouse + keyboard)
- Target: personal use on Tab S10 Ultra

### Phase 2 (v0.2.0) — "It's polished"

- Native Jagex OAuth2 login
- Touch-as-trackpad input overlay
- GPU acceleration via Zink + Turnip (OpenGL 4.6 over Vulkan)
- Better error handling and recovery
- Settings screen (display resolution, audio toggle, launch flags)

### Phase 3 (v0.3.0) — "Others can use it"

- Distributable APK (signed, proper onboarding)
- Device compatibility checks (RAM, chipset, storage)
- Non-DeX tablet support (touch-only)
- Automatic Termux/dependency management
- Documentation and troubleshooting guide

## Error Handling & Recovery

### Setup Failures

- Each setup step is idempotent — "Retry" re-runs just that step
- Termux install failure: prompt manual install from F-Droid (with link)
- Package install failure: "Check your connection and retry"
- Setup state is checkpointed — resumes where it left off if interrupted

### Launch Failures

- Proot won't start: check Termux is installed, offer to re-run setup
- RuneLite .jar crashes: show Java error in scrollable log view with "Copy Log" button
- Display fails to connect: retry once, then show manual Termux:X11 instructions

### Auth Failures

- Missing/invalid credentials: prompt re-import (MVP) or re-login (Phase 2)
- Revoked session: clear stored creds, prompt fresh auth
- Never silently fail on auth

### General Approach

- Show errors plainly with what went wrong and what to do
- "View Logs" in settings shows recent launch output
- No silent retries — always inform the user

## Testing Strategy

### Unit Tests

- Auth Manager: credential parsing, secure storage, env var formatting
- Update Manager: version comparison, API response parsing, offline fallback
- Setup Wizard: state checkpointing, step completion detection

### Integration Tests

- Setup flow: mock Termux/proot commands, verify install sequence
- Launch flow: mock proot session, verify env vars and command construction
- Update flow: mock GitHub API, verify download-and-replace logic

### Manual Testing

- End-to-end (Termux + proot + RuneLite) requires physical device
- MVP testing = running on Tab S10 Ultra
- Checklist: setup completes, credentials load, RuneLite launches, game playable, updates work

### Out of Scope

- No UI snapshot tests (app is too simple)
- No performance benchmarks (Phase 2 concern)
- No multi-device testing until Phase 3
