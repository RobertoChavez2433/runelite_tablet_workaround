# Termux Feature Overview

## Purpose

The Termux layer provides the bridge between the Android app and the Linux environment where RuneLite runs. It uses Termux's RUN_COMMAND intent API to send shell commands and receive results asynchronously.

## Key Capabilities

- **Send commands** to Termux via RUN_COMMAND broadcast intent
- **Receive results** (stdout, stderr, exitCode) via BroadcastReceiver with PendingIntent callback
- **Check Termux installation** status via PackageManager
- **Execution ID tracking** for correlating commands with their results

## How It Works

1. App generates a unique execution ID (AtomicInteger)
2. Creates a CompletableDeferred for the result
3. Sends RUN_COMMAND intent with PendingIntent for callback
4. Termux executes the command and sends result back via the PendingIntent
5. BroadcastReceiver extracts result from Bundle and completes the Deferred
6. Caller awaits result with timeout

## Key Files

| File | Role |
|------|------|
| `TermuxCommandRunner.kt` | Send commands via RUN_COMMAND intent |
| `TermuxResultService.kt` | Receive results via BroadcastReceiver |
| `TermuxPackageHelper.kt` | Check if Termux is installed |

## Prerequisites

- Termux installed (from GitHub Releases, not Play Store)
- `allow-external-apps` set in `~/.termux/termux.properties`
- `com.termux.permission.RUN_COMMAND` permission granted

## Related

- Constraints: `architecture-decisions/termux-constraints.md`
- Rules: `rules/termux-integration.md`
- Defects: `defects/_defects-termux.md`
