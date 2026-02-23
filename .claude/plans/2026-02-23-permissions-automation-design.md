# Permissions Automation Design

**Date**: 2026-02-23
**Status**: Approved
**Goal**: Replace manual 3-step permissions configuration with a guided, mostly-automated flow

## Problem

The "Configure Permissions" setup step requires users to:
1. Open Termux, type a command to set `allow-external-apps=true`
2. Grant RUN_COMMAND permission via Settings
3. Set battery optimization to Unrestricted for Termux

This is friction-heavy and error-prone. Users must leave the app, navigate Termux CLI, and find buried Android settings.

## Research Findings

| Step | Automatable? | Reason |
|------|-------------|--------|
| `allow-external-apps=true` | **No** | Chicken-and-egg: RUN_COMMAND needs this setting, but we need RUN_COMMAND to write the file. Android blocks cross-app private data access. |
| RUN_COMMAND permission | **Yes** | Standard `requestPermissions()` flow — Android shows dialog, user taps Allow |
| Battery optimization | **Mostly** | `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows system dialog. Samsung fallback needed. |

## Design

### Flow Overview

After detecting Termux + Termux:X11 are installed, execute a **3-phase sequence** with a consent screen:

### Phase 1 — Termux Config (manual, guided copy-paste)

- Screen explains: "We need to enable Termux to accept commands from our app"
- Prominent **"Copy Command"** button copies `echo 'allow-external-apps=true' >> ~/.termux/termux.properties` to clipboard
- **"Open Termux"** button launches Termux (also copies to clipboard if not already copied)
- User pastes command in Termux, hits enter, returns to our app
- **onResume** fires a test `RUN_COMMAND` (`echo RLT_OK`)
  - If stdout contains `RLT_OK` → Phase 1 passes instantly, proceed to Phase 2
  - If test fails → show "Not detected yet — tap to retry" (no scary error)

### Phase 2 — RUN_COMMAND Permission (automatic)

- Immediately after Phase 1 succeeds, call `requestPermissions(["com.termux.permission.RUN_COMMAND"])`
- Standard Android dialog appears, user taps "Allow"
- One tap, no custom explanation needed

### Phase 3 — Battery Optimization (automatic)

- Fire `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` for `com.termux`
- System dialog: "Let Termux run in background?" — user taps "Allow"
- Also request for our own app (`com.runelitetablet`) if not already exempt
- Samsung fallback: if intent fails, open Termux's app info settings page
- Check via `PowerManager.isIgnoringBatteryOptimizations("com.termux")`

### After All 3 Pass

Step automatically marks as Completed. Setup continues to Install Linux Environment.

## State Machine

```
PermissionPhase.TermuxConfig
  → (onResume: test RUN_COMMAND succeeds)
  → PermissionPhase.RuntimePermission
  → (requestPermissions callback: granted)
  → PermissionPhase.BatteryOptimization
  → (onResume: isIgnoringBatteryOptimizations == true)
  → PermissionPhase.Complete
```

## Step Reordering

Move permissions step to run **before** any Termux work:
1. Install Termux ✓
2. Install Termux:X11 ✓
3. **Configure Permissions** ← moved up, before proot/java/runelite
4. Install Linux Environment
5. Install Java Runtime
6. Download RuneLite
7. Verify Setup

## Files to Change

| File | Change |
|------|--------|
| `SetupOrchestrator.kt` | Replace `handlePermissionsStep()` with phased permission flow, add test RUN_COMMAND verify, add battery optimization request |
| `SetupViewModel.kt` | Add `PermissionPhase` state, onResume auto-verify trigger, clipboard copy helper |
| `SetupScreen.kt` | New permission consent UI replacing bottom sheet — shows phase-specific content |
| `SetupStep.kt` | Move permissions step position (after Termux installs, before environment setup) |
| `AndroidManifest.xml` | Add `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission |

## Files Unchanged

- `TermuxCommandRunner.kt` — no changes
- `LocalhostAuthServer.kt` — no changes
- All shell scripts — no changes
- `AuthWebViewActivity.kt` — no changes

## Implementation Notes

- **Clipboard**: Use `android.content.ClipboardManager` to copy command
- **Launch Termux**: `packageManager.getLaunchIntentForPackage("com.termux")`
- **No new Activity**: All happens within existing SetupScreen via state-driven UI
- **Manifest permission**: `android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
- **Samsung quirk**: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` may not work on all Samsung devices. Fallback to `ACTION_APPLICATION_DETAILS_SETTINGS` with `package:com.termux`
