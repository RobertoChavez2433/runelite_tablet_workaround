# Debug Logging System

## Quick Start

1. Build and deploy a DEBUG build to the device
2. Forward the debug port: `adb forward tcp:8099 tcp:8099`
3. Open `http://localhost:8099/` in your browser
4. The live log viewer shows all events in real-time

## Architecture

The logging system has three destinations, all active in DEBUG builds:

- **Logcat** — standard Android log output
- **File** — rotated log files in app's cache directory (`rlt-session-*.log`, `rlt-perf-*.log`)
- **WebSocket** — live structured JSON stream on port 8099

All three receive identical events. The WebSocket endpoint serves both the HTML viewer (at `/`) and a programmatic JSON API (at `/ws`).

## WebSocket API

Connect to `ws://device-ip:8099/ws` (or `ws://localhost:8099/ws` after adb forward).

Each message is a JSON object:

```json
{
  "ts": 1712000000000,
  "elapsed": 5234,
  "level": "D",
  "tag": "FRAME",
  "msg": "fps=118.2 jank=1 p99=9.1ms heap=45MB",
  "thread": "main",
  "correlationId": "launch-a3f7",
  "source": "kotlin",
  "file": "HybridX11HostActivity.kt",
  "line": 97
}
```

### Fields

| Field | Type | Description |
|-------|------|-------------|
| ts | long | Unix timestamp (ms) |
| elapsed | long | Milliseconds since server start |
| level | string | D, I, W, or E |
| tag | string | Log tag (FRAME, JANK, AUTH, CMD, etc.) |
| msg | string | Log message |
| thread | string | Thread name |
| correlationId | string? | Operation trace ID (e.g., `setup-a3f7`) |
| source | string | `kotlin`, `native`, or `shell` |
| file | string | Source file name |
| line | int | Source line number |

### Sources

- `kotlin` — Kotlin/JVM code via `Logger` interface
- `native` — C code via `__android_log_print()` -> logcat bridge -> WebSocket
- `shell` — Termux command stdout/stderr via `TermuxCommandRunner`

## HTML Viewer

The viewer at `http://localhost:8099/` provides:

- **Filters**: text, level, source, tag, correlationId
- **Color coding**: errors (red), warnings (yellow), jank (red bold), handoffs (teal)
- **Timeline**: elapsed milliseconds for cross-boundary correlation
- **Pause/Resume**: buffer events while paused, flush on resume

## Correlation IDs

Every top-level operation generates a correlation ID (format: `{action}-{4hex}`):

- `setup-a3f7` — setup orchestration chain
- `auth-b2c1` — authentication flow
- `launch-d4e5` — launch sequence
- `frames-f6g7` — rendering session

Nested operations use `/` separator: `launch-d4e5/auth-refresh-h8i9`

Filter by correlationId in the viewer to trace a complete operation across Kotlin -> JNI -> native -> shell.

## Perf Logs

Frame-rate data writes to a separate `rlt-perf-*.log` file to avoid polluting general logs. Tags routed to perf: FRAME, JANK, SURFACE, GL, BUFFER.

## File Descriptor Tracking

In DEBUG builds, `FdTracker` monitors every fd open/close/transfer/detach. On `onPause()`, it dumps any fds that haven't been closed — potential leak suspects.

## Programmatic Access

The systematic-debug skill connects to `ws://localhost:8099/ws` and receives the same JSON stream. No separate API needed.
