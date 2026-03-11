# Termux Feature Architecture

## Component Design

```
TermuxCommandRunner
  ├── AtomicInteger (execution ID generator)
  ├── ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>
  ├── send() → RUN_COMMAND intent with PendingIntent
  └── await() → withTimeout { deferred.await() }

TermuxResultService (BroadcastReceiver)
  ├── onReceive() → extract execution_id from intent
  ├── getBundleExtra("result") → extract stdout/stderr/exitCode
  └── complete(deferred) with TermuxResult

TermuxPackageHelper
  └── isInstalled() → PackageManager.getPackageInfo()
```

## Threading Model

| Operation | Dispatcher | Reason |
|-----------|-----------|--------|
| Intent send | Main | Requires Activity context |
| Result receive | Main | BroadcastReceiver runs on main thread |
| Result await | Caller's dispatcher | withTimeout wraps CompletableDeferred |

## Async Pattern (CompletableDeferred)

1. Generate unique ID via `AtomicInteger.incrementAndGet()`
2. Create `CompletableDeferred<TermuxResult>()`
3. Store in `ConcurrentHashMap` keyed by execution ID
4. Send RUN_COMMAND intent with PendingIntent carrying the execution ID
5. `withTimeout(timeout) { deferred.await() }` — caller blocks until result
6. On timeout: remove from map, throw TimeoutCancellationException
7. On result: remove from map, return TermuxResult

## Key Design Decisions

- **PendingIntent FLAG_MUTABLE** — required because Termux fills in result extras via `send(ctx, RESULT_OK, fillInIntent)`
- **Bundle extraction** — Termux wraps results in a Bundle, not flat extras
- **AtomicInteger IDs** — simple, sequential, consumed immediately (narrow collision window)
- **ConcurrentHashMap** — thread-safe for cross-thread async callbacks

## Data Flow

```
App → RUN_COMMAND intent → Termux → execute command → PendingIntent.send()
                                                          ↓
App ← TermuxResult ← CompletableDeferred.complete() ← BroadcastReceiver
```
