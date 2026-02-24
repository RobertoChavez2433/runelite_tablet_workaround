# Lifecycle Management + GPU Acceleration Design

**Date**: 2026-02-23 | **Status**: Approved

## Design Decisions

| Decision | Choice |
|---|---|
| Swipe-to-close behavior | Keep RuneLite running |
| Notification actions | "Switch to Game" + "Stop Game" |
| GPU setup | Automated setup step |
| GPU fallback | Auto-fallback to software rendering |
| Session detection | Auto-detect + dual buttons |
| Health poll interval | 15 seconds |
| Architecture | Foreground Service + Shell Scripts (Approach A) |

---

## Architecture Overview

Two independent feature layers added to the existing app:

**Lifecycle Layer** (`session/` package):
- `RuneLiteSessionService` — Foreground Service that owns the game session lifecycle
- `SessionState` — Sealed class: `Idle`, `Starting`, `Running`, `Stopped`, `Error(msg)`
- `SessionHealthMonitor` — Polls Termux every 15s to check if RuneLite is alive
- `shutdown-session.sh` — Shell script for ordered process teardown

**GPU Layer** (changes to existing `setup/` + `assets/scripts/`):
- New setup step in `SetupOrchestrator` — downloads + installs Mesa/Turnip packages in proot
- `setup-gpu.sh` — Shell script to install GPU packages inside proot Ubuntu
- Modified `launch-runelite.sh` — adds `--bind /dev/kgsl-3d0`, GPU env vars, runtime fallback

**Data flow**:
```
User taps Launch → ViewModel → starts SessionService → Service sends RUN_COMMAND → Termux runs launch-runelite.sh
                                    ↓
                              starts health polling (every 15s: pgrep via TermuxCommandRunner)
                                    ↓
                              updates SessionState flow → ViewModel observes → UI updates
                                    ↓
                              updates notification ("Running - 5 min" / "Stopped")
```

---

## Lifecycle — Foreground Service Design

### RuneLiteSessionService (`session/RuneLiteSessionService.kt`)

**Service lifecycle**:
- Started via `context.startForegroundService(intent)` when user taps Launch
- Immediately calls `startForeground()` with notification — mandatory within 5 seconds
- `START_STICKY` — system restarts if killed, service re-checks if RuneLite is still running
- Stops itself when health monitor detects RuneLite exited, or user taps "Stop Game"

**Actions**:
- `ACTION_START_SESSION` — start service + launch game
- `ACTION_STOP_SESSION` — graceful shutdown
- `ACTION_SWITCH_TO_GAME` — open Termux:X11
- `ACTION_CHECK_SESSION` — re-check if game is running (called on app resume)

**Communication pattern**: Companion object with `MutableStateFlow` — same-process, no binding needed:

```kotlin
companion object {
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _sessionStartTime = MutableStateFlow(0L)
    val sessionStartTime: StateFlow<Long> = _sessionStartTime.asStateFlow()
}
```

**`onTaskRemoved()`**: Keep running (user decision). Log only.

**Notification** (channel: `runelite_session`):
- Title: "RuneLite is running"
- Text: "Playing for 12 minutes" (updated every health poll)
- Actions: "Switch to Game" (PendingIntent), "Stop Game" (PendingIntent)
- Ongoing, low priority (no sound/vibration), auto-cancel on stop

**Manifest additions**:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".session.RuneLiteSessionService"
    android:foregroundServiceType="specialUse"
    android:exported="false">
    <property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="Managing RuneLite game session processes in Termux" />
</service>
```

---

## Health Monitoring + Session Detection

### SessionHealthMonitor (`session/SessionHealthMonitor.kt`)

**Mechanism — PID file + pgrep fallback**:

1. `launch-runelite.sh` writes RuneLite's Java PID to `$HOME/.rlt-session.pid` after java starts
2. Health monitor reads PID file via TermuxCommandRunner, verifies with `kill -0`
3. If PID file missing/stale, falls back to `pgrep -f 'net.runelite.client.RuneLite'`

```kotlin
class SessionHealthMonitor(
    private val commandRunner: TermuxCommandRunner,
    private val scope: CoroutineScope
) {
    fun startPolling(onStateChanged: (SessionState) -> Unit): Job {
        return scope.launch {
            while (isActive) {
                val state = checkHealth()
                onStateChanged(state)
                delay(15_000L)
            }
        }
    }

    suspend fun checkHealth(): SessionState {
        val result = commandRunner.execute(
            commandPath = "/data/data/com.termux/files/usr/bin/bash",
            arguments = arrayOf("-c", """
                PID=$(cat "$HOME/.rlt-session.pid" 2>/dev/null)
                if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
                    echo "RUNNING"
                elif pgrep -f 'net.runelite.client.RuneLite' > /dev/null 2>&1; then
                    echo "RUNNING"
                else
                    echo "STOPPED"
                fi
            """.trimIndent()),
            background = true,
            timeoutMs = 5_000L
        )
        return when (result.stdout?.trim()) {
            "RUNNING" -> SessionState.Running
            "STOPPED" -> SessionState.Stopped
            else -> SessionState.Error("Health check failed")
        }
    }
}
```

**Error handling**:
- Termux unavailable: catch, return `SessionState.Error("Termux unavailable")`
- Timeout (5s): keep previous state (don't flap)
- 3 consecutive `Stopped` before transitioning from `Running` → `Stopped` (debounce)

### Launch Screen UI Changes

| SessionState | Launch Screen Shows |
|---|---|
| `Idle` | "Launch RuneLite" button |
| `Starting` | "Launching..." with spinner |
| `Running` | "Switch to Game" + "Stop Game" buttons, elapsed time |
| `Stopped` | "Launch RuneLite" button + "Last session ended" message |
| `Error(msg)` | "Launch RuneLite" button + error toast |

**On app resume**: ViewModel sends `ACTION_CHECK_SESSION` for immediate health check.

### PID File in launch-runelite.sh

```bash
# Background java, write PID, then wait
java ... &
JAVA_PID=$!
echo "$JAVA_PID" > "$HOME/.rlt-session.pid"
wait $JAVA_PID
rm -f "$HOME/.rlt-session.pid"
```

---

## Graceful Shutdown

### shutdown-session.sh (`assets/scripts/shutdown-session.sh`)

Ordered teardown respecting dependency chain:

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -uo pipefail

echo "=== Graceful shutdown $(date) ==="

# 1. RuneLite (Java) — SIGTERM first, SIGKILL fallback
JAVA_PID=$(cat "$HOME/.rlt-session.pid" 2>/dev/null)
if [ -z "$JAVA_PID" ]; then
    JAVA_PID=$(pgrep -f 'net.runelite.client.RuneLite' 2>/dev/null | head -1)
fi
if [ -n "$JAVA_PID" ]; then
    echo "Stopping RuneLite (PID $JAVA_PID)..."
    kill "$JAVA_PID" 2>/dev/null
    for i in $(seq 1 50); do
        kill -0 "$JAVA_PID" 2>/dev/null || break
        sleep 0.1
    done
    kill -9 "$JAVA_PID" 2>/dev/null || true
fi

# 2. Openbox
pkill -f 'openbox' 2>/dev/null || true

# 3. Proot sessions
pkill -f 'proot-distro' 2>/dev/null || true
pkill -f 'proot --' 2>/dev/null || true

# 4. PulseAudio
pulseaudio --kill 2>/dev/null || true

# 5. X11 server process
pkill -f 'termux-x11' 2>/dev/null || true

# 6. Termux:X11 Android app
am broadcast -a com.termux.x11.ACTION_STOP --user 0 2>/dev/null || true

# Cleanup files
rm -f "$HOME/.rlt-session.pid" 2>/dev/null || true
rm -f "$PREFIX/tmp/.rlt-creds-"*.sh 2>/dev/null || true
rm -f "$HOME/.rlt-launch-env.sh" 2>/dev/null || true

echo "SHUTDOWN_COMPLETE"
```

**Service-side**: 15s timeout for shutdown script. If exceeded, fallback to aggressive pkill. Deployed via ScriptManager alongside existing scripts.

---

## GPU Acceleration — Setup + Runtime

### Setup Step: GPU Driver Installation

New step in `SetupOrchestrator` after environment setup. Step 7: `InstallGpuDrivers`.

### setup-gpu.sh (`assets/scripts/setup-gpu.sh`)

```bash
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

proot-distro login ubuntu -- bash -c '
    set -euo pipefail

    GPU_MARKER="/root/.rlt-gpu-installed"

    # Idempotent — skip if already installed
    if [ -f "$GPU_MARKER" ]; then
        echo "GPU drivers already installed"
        if [ -f "/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so" ] || \
           [ -f "/usr/lib/aarch64-linux-gnu/libEGL_mesa.so" ]; then
            echo "GPU_SETUP_COMPLETE"
            exit 0
        fi
        echo "GPU marker exists but libraries missing — reinstalling"
    fi

    echo "Installing Mesa + Turnip GPU drivers..."

    apt-get update -qq
    apt-get install -y -qq wget libx11-6 libxext6 libxfixes3 libxshmfence1 \
        libxxf86vm1 libdrm2 libexpat1 libwayland-client0 libelf1 \
        zlib1g libzstd1 > /dev/null 2>&1

    MESA_VERSION="26.1.0"
    MESA_BASE="https://github.com/lfdevs/mesa-for-android-container/releases/download"
    MESA_TAG="mesa-${MESA_VERSION}"

    cd /tmp

    echo "Downloading Mesa ${MESA_VERSION}..."
    wget -q -O mesa.tar.gz "${MESA_BASE}/${MESA_TAG}/mesa-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"

    echo "Downloading Turnip driver..."
    wget -q -O turnip.tar.gz "${MESA_BASE}/${MESA_TAG}/turnip-${MESA_VERSION}_ubuntu_noble_arm64.tar.gz"

    echo "Installing Mesa..."
    tar -zxf mesa.tar.gz -C /

    echo "Installing Turnip..."
    tar -zxf turnip.tar.gz -C /

    ldconfig

    rm -f /tmp/mesa.tar.gz /tmp/turnip.tar.gz
    echo "${MESA_VERSION}" > "$GPU_MARKER"
    echo "GPU_SETUP_COMPLETE"
'
```

### launch-runelite.sh Changes

**1. Bind-mount GPU device node** (proot-distro login):
```bash
proot-distro login ubuntu \
    --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix" \
    --bind "$PREFIX/tmp:/tmp" \
    --bind /dev/kgsl-3d0:/dev/kgsl-3d0 \
    -- bash -c "..."
```

**2. GPU runtime detection** (inside proot bash block):
```bash
GPU_AVAILABLE=false
if [ -e /dev/kgsl-3d0 ]; then
    export MESA_LOADER_DRIVER_OVERRIDE=zink
    export GALLIUM_DRIVER=zink
    export TU_DEBUG=noconform
    export MESA_NO_ERROR=1
    export ZINK_DESCRIPTORS=lazy

    GL_RENDERER=$(glxinfo 2>/dev/null | grep -i 'opengl renderer' || true)
    if echo "$GL_RENDERER" | grep -qi 'zink'; then
        GPU_AVAILABLE=true
        echo "GPU acceleration: ENABLED (Zink+Turnip)"
    else
        echo "GPU acceleration: FAILED (falling back to software)"
        unset MESA_LOADER_DRIVER_OVERRIDE GALLIUM_DRIVER TU_DEBUG MESA_NO_ERROR ZINK_DESCRIPTORS
    fi
else
    echo "GPU acceleration: UNAVAILABLE (no /dev/kgsl-3d0)"
fi
```

**3. Conditional JVM flags**:
```bash
if [ "$GPU_AVAILABLE" = true ]; then
    GPU_FLAGS=""
else
    GPU_FLAGS="-Dsun.java2d.opengl=false"
fi

java -Xmx2g -Xms512m ... $GPU_FLAGS ...
```

RuneLite GPU plugin auto-enables when it detects OpenGL 4.0+. No explicit flag needed.

---

## Error Handling + Edge Cases

### Lifecycle Edge Cases

| Scenario | Handling |
|---|---|
| Termux force-stopped by user | Health poll catches exception → `SessionState.Error("Termux unavailable")`. Service stops after 3 consecutive failures. |
| Android kills our process (OOM) | `START_STICKY` restarts service. Reads `sessionActive` from SharedPreferences, runs health check. Resume or clear. |
| Double-tap Launch | Guard with SessionState. If `Starting` or `Running`, ignore. |
| Health check timeout | Keep previous state. After 3 consecutive timeouts → `Error("Health check unresponsive")`. |
| Service restart after reboot | Health check finds nothing → clears SharedPreferences → stops self. |
| POST_NOTIFICATIONS denied | Service works, notification invisible. Soft prompt during setup, non-blocking. |

### GPU Edge Cases

| Scenario | Handling |
|---|---|
| `/dev/kgsl-3d0` doesn't exist | Skip GPU env vars, log "UNAVAILABLE", software rendering. |
| Mesa download fails | Setup step fails with retry button. GPU step non-blocking — can launch with software. |
| glxinfo reports llvmpipe despite env vars | Fallback: unset vars, log "FAILED", continue software rendering. |
| Zink crash during startup | Health monitor detects Stopped. Re-launch auto-fallbacks via glxinfo check. |
| Mesa package URL 404 | Setup step error: "GPU driver download failed." User can retry or skip. |
| proot bind-mount fails | Silent failure. `/dev/kgsl-3d0` check inside proot finds missing → software fallback. |

### SharedPreferences for Service State

```kotlin
// Written by service on start
prefs.edit {
    putBoolean("session_active", true)
    putLong("session_start_time", System.currentTimeMillis())
}
// Cleared on stop
prefs.edit {
    putBoolean("session_active", false)
    remove("session_start_time")
}
```

---

## Implementation Phases

### Phase 1: Lifecycle — Session Service + Health Monitoring

**New files**:
- `session/RuneLiteSessionService.kt` — Foreground Service, notification, session state
- `session/SessionHealthMonitor.kt` — 15s polling via TermuxCommandRunner
- `session/SessionState.kt` — Sealed class
- `assets/scripts/shutdown-session.sh` — Ordered process teardown

**Modified files**:
- `AndroidManifest.xml` — Permissions + service declaration
- `SetupViewModel.kt` — Observe SessionState, start/stop service
- `ui/SetupScreen.kt` — Conditional buttons (Launch vs Switch/Stop)
- `setup/ScriptManager.kt` — Deploy shutdown-session.sh
- `RuneLiteTabletApp.kt` — Create notification channel
- `assets/scripts/launch-runelite.sh` — PID file + background java pattern

### Phase 2: GPU — Setup Step + Launch Script

**New files**:
- `assets/scripts/setup-gpu.sh` — Mesa/Turnip installation in proot

**Modified files**:
- `setup/SetupOrchestrator.kt` — Add GPU driver install step
- `setup/SetupStep.kt` — Add GpuDrivers step variant
- `assets/scripts/launch-runelite.sh` — bind kgsl, GPU env vars, runtime detection, conditional flags

### Phase 3: Polish + Integration

**Modified files**:
- `setup/SetupOrchestrator.kt` — POST_NOTIFICATIONS permission check (soft, non-blocking)
- `ui/SetupScreen.kt` — Session elapsed time, error states

### Order

1. Phase 1 first — lifecycle is the foundation, service must exist before GPU testing
2. Phase 2 second — GPU setup + launch script changes, test on device
3. Phase 3 last — polish UI, notification permission, edge cases

**Total**: ~4 new files, ~8 modified files. Phases independently testable.
