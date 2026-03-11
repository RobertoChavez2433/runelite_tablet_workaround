# Setup Feature Overview

## Purpose

The Setup layer orchestrates all 7 setup steps required to get RuneLite running on the tablet. It manages step state, progress tracking, and error recovery.

## 7 Setup Steps

| Step | Implementation | Description |
|------|---------------|-------------|
| 1. Check Termux | TermuxPackageHelper | Verify Termux is installed |
| 2. Install Termux | ApkDownloader + ApkInstaller | Download from GitHub, install via PackageInstaller |
| 3. Grant Permissions | SetupActions callback | Guide user to enable RUN_COMMAND |
| 4. Copy Scripts | ScriptManager | Extract shell scripts from APK assets |
| 5. Setup Environment | TermuxCommandRunner | Run setup-environment.sh via Termux |
| 6. Download RuneLite | TermuxCommandRunner | Download RuneLite .jar inside proot |
| 7. Launch RuneLite | TermuxCommandRunner | Start RuneLite with X11 display |

## Key Capabilities

- **Sequential step execution** with progress tracking
- **State persistence** via stateStore for crash recovery
- **Marker-based reconciliation** to detect environment changes
- **Error recovery** with step retry and full reset

## Key Files

| File | Role |
|------|------|
| `SetupStep.kt` | Sealed class for step status (Pending/InProgress/Completed/Failed) |
| `SetupOrchestrator.kt` | Orchestrates all 7 setup steps |
| `SetupViewModel.kt` | ViewModel exposing StateFlow<SetupState> |
| `ScriptManager.kt` | Extract shell scripts from APK assets |

## Related

- Constraints: `architecture-decisions/setup-constraints.md`
- Defects: `defects/_defects-setup.md`
