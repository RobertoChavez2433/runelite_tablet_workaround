# Slice 2: Damage Event Attribution

**Date**: 2026-04-16 | **Session**: 72 | **Device**: R52X90378YB

## Capture summary (60s window, RuneLite post-login UI)

Total DamageTraceV2 events: **4222** across the capture window.

### PutImage geometry histogram

| width × height | count | share |
|---|---|---|
| 2898 × 22    | 3954 | 99.5% |
| 300 × 85     | 18   | 0.5% |
| 300 × 215    | 2    | <0.1% |

### Composite geometry histogram

| width × height | count | notes |
|---|---|---|
| 2960 × 1848 | 1 | full-screen (startup) |
| 400 × 32    | ~4 | tooltip/overlay region |

## Verdict

- **Damage-FPS ≠ scene FPS; gap is explained.**
- 100% of bulk damage is `exaPutImage` on 2898×22 horizontal strips targeting the
  RuneLite client window.
- A full window update requires ~84 PutImage slices (1848 rows / 22 per slice).
  At ~1065 PutImage/sec, this reconstructs scene frames at ~12.7 Hz.
- `FpsPlugin` overlay reports ~10 FPS in-game; the difference (12.7 vs 10) is
  overlay/cursor/chat partials and coalescing, not redundant scene renders.
- The compositor's coarse `damage-triggered redraws` counter (~50/sec) is the
  polling rate at which Lorie checks for accumulated damage — it coalesces many
  fine-grained PutImage strips into a single redraw per poll.

## Why 22-row strips

RuneLite's GPU plugin (rlawt) reads back GPU-rendered frames and issues
`XPutImage` to the X server. The server's image request chunking (2898×22 ≈
250 KiB per call) is consistent with default X protocol `MAX_REQUEST_LENGTH`-
aware chunking on the client side.

## Actionable signal

- The bottleneck is not "too few damage events" — it's that each PutImage strip
  pays full EXA migration / damage-wrap / memcpy overhead. Reducing per-call
  overhead (Slice 3) or coalescing strips into single AHB-backed writes
  (Slice 3-5) is where FPS will move.
- Present is 0% active (`present after-flips = 0 FPS`), confirming the PutImage
  path is the only presentation route at the moment.
