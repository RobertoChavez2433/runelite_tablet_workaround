# Phase 1 UX Revert + Termux Extra Keys Configuration

**Date**: 2026-02-23
**Status**: Approved
**Session**: 26

## Problem

Phase 1 setup requires users to configure Termux's `allow-external-apps=true` property. Last session explored two approaches:
1. **Clipboard copy-paste** — Android clipboard corrupted single quotes to curly quotes, breaking the command
2. **MediaStore script + type short command** — User found typing harder than pasting

Additionally, Termux lacks modifier keys (Ctrl, Alt, Esc) when using a standard keyboard like SwiftKey, making ongoing terminal interaction difficult.

## Solution

Two changes bundled into one design:

1. **Revert Phase 1 UI to clipboard copy-paste** with a no-quotes combined command
2. **Configure Termux extra keys** (2-row terminal shortcut bar) as part of the same command

### Key Insight

Since the user must paste one command into Termux anyway (chicken-and-egg: can't use RUN_COMMAND until `allow-external-apps` is set), we bundle the extra keys config into that same command. One paste, two improvements.

## Research Summary

Three parallel research agents investigated:

### Codebase Agent
- Current Phase 1 uses MediaStore to write a script to `/sdcard/Download/rlt-setup.sh`, then asks user to type `bash /sdcard/Download/rlt-setup.sh` in Termux
- `SetupViewModel.kt` has `writeSetupScript()` and MediaStore logic to revert
- Verification works via `RUN_COMMAND` intent sending `echo ok` — stays unchanged

### Web Search: Custom Keyboard Options
| Option | Verdict |
|--------|---------|
| Termux built-in extra keys row | **Best option** — zero code, pure config, 2-row layout above any keyboard |
| Custom Android IME (InputMethodService) | Over-engineered — weeks of work, SwiftKey has 100+ engineers |
| Hacker's Keyboard fork | Too much scope — Gingerbread-era Java, needs full rewrite for modern UX |
| Floating overlay (SYSTEM_ALERT_WINDOW) | Can't inject keystrokes — marginal improvement for significant permission friction |
| Accessibility Service | Policy violation — Google Play prohibits non-accessibility use, app would be rejected |
| Android Bubbles API | Semantically wrong, can't type into Termux |

### Web Search: Paste into Termux
| Option | Verdict |
|--------|---------|
| Set clipboard + user pastes | **Best option** — only reliable cross-app approach |
| Accessibility Service paste | Google Play policy violation, Termux custom TerminalView may not support ACTION_PASTE |
| INJECT_EVENTS permission | System-only, impossible for regular apps |
| Termux RUN_COMMAND | Chicken-and-egg — requires the property we're trying to set |
| termux-clipboard-set API | Internal Termux command, not externally callable |

**Conclusion**: Android fundamentally prevents one app from injecting text into another app's UI. Clipboard copy + user paste is the best achievable UX.

## The Combined Command

Single command copied to clipboard that configures both `allow-external-apps` and extra keys:

```bash
mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties && echo "extra-keys = [['ESC','CTRL','ALT','LEFT','DOWN','UP','RIGHT','TAB'],['~','/','-','|','HOME','END','PGUP','PGDN']]" >> ~/.termux/termux.properties && termux-reload-settings && echo Done
```

**Properties:**
- `mkdir -p ~/.termux` — idempotent, creates dir if needed
- No single quotes in the pasted text — avoids Android clipboard curly-quote corruption
- Double quotes are safe — Android clipboard doesn't corrupt them
- `termux-reload-settings` applies changes without Termux restart
- `echo Done` gives visual confirmation to user
- `&&` chain stops on first error
- Command is long but user never reads/types it — it's auto-copied to clipboard

## Extra Keys Layout

2-row layout configured in `termux.properties`:

```
┌──────────────────────────────────────────────┐
│ [ESC] [CTRL] [ALT] [<-] [v] [^] [->] [TAB]  │
│ [~]   [/]    [-]   [|] [HOME][END][PGUP][PGDN]│
└──────────────────────────────────────────────┘
```

- Row 1: Modifiers (ESC, CTRL, ALT) + arrows + TAB
- Row 2: Common terminal characters + page navigation
- Appears above the user's normal keyboard (SwiftKey, Gboard, etc.)
- Ctrl+V = Tap CTRL then V on keyboard; Ctrl+C = Tap CTRL then C
- Termux paste = Ctrl+Alt+V (tap CTRL, tap ALT, tap V)

## Timeline

| When | User does | Extra keys? |
|------|-----------|-------------|
| Phase 1 (one-time) | Long-press paste + Enter | No — this paste installs them |
| Setup steps 2-7 | Nothing — automated via RUN_COMMAND | Yes, but not needed |
| Playing RuneScape | Terminal via Termux:X11 | Yes — Ctrl, arrows, etc. |
| Troubleshooting | May open Termux manually | Yes — full 2-row keys |

## UI Flow

```
┌─────────────────────────────────────────────┐
│  Step 1 of 3: Enable Termux Commands        │
│                                             │
│  We need to configure Termux to accept      │
│  commands from our app and add helpful       │
│  terminal shortcut keys.                     │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │  1. Tap "Copy & Open Termux" below  │    │
│  │  2. Long-press anywhere in Termux   │    │
│  │  3. Tap "Paste"                     │    │
│  │  4. Tap Enter on your keyboard      │    │
│  │  5. Come back here — we'll check    │    │
│  │     automatically!                  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │     Copy & Open Termux              │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │     Done — Check Now                │    │
│  └─────────────────────────────────────┘    │
│                                             │
└─────────────────────────────────────────────┘
```

- **Single button** "Copy & Open Termux" — copies to clipboard AND launches Termux in one tap
- Numbered steps are explicit — no ambiguity about long-press
- Auto-verify on `onResume` so user often doesn't need "Done — Check Now"
- "Done — Check Now" is a fallback if auto-verify didn't trigger

## File Changes

### SetupViewModel.kt
- **Remove**: `writeSetupScript()`, `_commandCopied` MediaStore tracking, MediaStore imports, `termuxTypeCommand`
- **Add**: `copyConfigAndOpenTermux()` — sets clipboard with combined command, launches Termux, sets `_commandCopied = true`
- **Keep**: Verification logic (`verifyPermissions()` via `echo ok`), `combine()` for `isPermissionStepActive`, all Session 25 bug fixes

### SetupScreen.kt
- **Remove**: "Type this command" display, MediaStore-related UI
- **Add**: Numbered instruction list (5 steps), single "Copy & Open Termux" button
- **Keep**: "Done — Check Now" button as fallback, phase progression UI

### SetupOrchestrator.kt
- **No changes** — all 3 Session 25 bug fixes stay (lazy `allSteps`, `MutableStateFlow`, `SecurityException` catch)

### AndroidManifest.xml
- **No changes needed**

## Error Handling

| Scenario | Handling |
|----------|----------|
| User pastes but command fails | `echo Done` won't print — user sees error. "Done — Check Now" fails verification, shows retry |
| User doesn't paste at all | Auto-verify on resume fails silently, "Done — Check Now" shows "Not yet configured" |
| Clipboard overwritten before paste | "Copy & Open Termux" can be tapped again to re-copy |
| `termux-reload-settings` not found | Older Termux — settings apply on next restart anyway. Config is already written |
| Extra keys config already exists | Appending duplicate is harmless — Termux uses last `extra-keys` line |

## Out of Scope

- Custom Android IME / keyboard fork — rejected as over-engineered
- Floating overlay buttons — can't inject keystrokes, marginal UX improvement
- Accessibility Service — Google Play policy violation
- Automating the Phase 1 paste — impossible due to Android security model
