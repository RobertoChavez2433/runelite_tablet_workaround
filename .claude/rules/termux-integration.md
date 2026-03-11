---
paths:
  - "**/termux/**/*.kt"
---

# Termux Integration Rules

## RUN_COMMAND Intent Protocol

- Termux wraps ALL result data in a `Bundle` extra with key `"result"` — NOT flat intent extras
- Extract via: `intent.getBundleExtra("result")?.getString("stdout")` etc.
- Key names inside the Bundle: `"stdout"`, `"stderr"`, `"exitCode"` (Int), `"err"` (Int, error code), `"errmsg"` (String)
- `"err"` value of `-1` means no error (Termux default sentinel) — only `errCode > 0` is a real error
- `execution_id` stays as top-level intent extra (baked into PendingIntent template, survives merge)

## Hard Rules

1. **PendingIntent MUST use FLAG_MUTABLE** — Termux calls `pendingIntent.send(ctx, RESULT_OK, fillInIntent)` and fill-in extras are silently dropped with `FLAG_IMMUTABLE`
2. **Use AtomicInteger for execution IDs** — not `nanoTime()` or random; sequential IDs consumed immediately with narrow collision window
3. **App's filesDir is unreadable by Termux** (different UID) — deploy everything via `TermuxCommandRunner` stdin, not by writing files to app-private storage
4. **`allow-external-apps` must be set** in `~/.termux/termux.properties` — without it, RUN_COMMAND intents are silently ignored
5. **RUN_COMMAND requires `com.termux.permission.RUN_COMMAND`** permission declared in manifest

## Soft Guidelines

- Always timeout CompletableDeferred.await() in TermuxCommandRunner (see coroutine-safety rules)
- Validate execution_id against `ConcurrentHashMap` — discard unknown IDs immediately
- Cancel PendingIntent after result is received (prevents replay)
- TermuxResultService and InstallResultReceiver should be `exported="false"`

## Termux Path Conventions

- Bash: `/data/data/com.termux/files/usr/bin/bash` (NOT `/bin/bash`)
- `$PREFIX` = `/data/data/com.termux/files/usr`
- `$HOME` = `/data/data/com.termux/files/home`
- `$PREFIX/tmp` is the real tmp dir, NOT `/tmp` (permission denied)
- X11 socket: `$PREFIX/tmp/.X11-unix/X0`
