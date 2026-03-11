---
paths:
  - "**/*.kt"
---

# Coroutine Safety Rules

## Dispatcher Mapping

| Operation | Dispatcher | Notes |
|-----------|-----------|-------|
| Network (OkHttp) | IO | Blocking I/O |
| File I/O | IO | Blocking I/O |
| PackageInstaller | IO | Session writes block |
| UI state updates | Main | StateFlow -> Compose |
| Termux intent send | Main | Requires Activity context |
| Termux result receive | Main | BroadcastReceiver |

## Hard Rules

1. **Never swallow CancellationException** — always re-throw or catch specific exception types instead of bare `Exception`
2. **Always timeout CompletableDeferred.await()** — use `withTimeout()` wrapper; never unbounded await
3. **Use structured concurrency** — no `GlobalScope`; use `viewModelScope`, `lifecycleScope`, or custom `CoroutineScope` with proper cancellation
4. **Catch TimeoutCancellationException BEFORE CancellationException** — `TimeoutCancellationException` is a subclass; wrong catch order makes the timeout catch unreachable (dead code)
5. **Always `response.use {}`** for OkHttp responses — `Response` implements `Closeable`; forgetting prevents connection pool reuse

## Soft Guidelines

- Derive StateFlow with `.map().stateIn()` instead of manual `launch { collect {} }` collectors
- Use `@Volatile` for fields accessed across dispatchers (ARM64 torn pointer risk)
- Use `ConcurrentHashMap<Int, CompletableDeferred>` for cross-thread async callbacks
- Check `coroutineContext.isActive` in blocking loops for cooperative cancellation
- Wrap blocking I/O (assets, PackageInstaller sessions) in `withContext(Dispatchers.IO)`

## Anti-Patterns

- `catch (e: Exception)` that silently swallows `CancellationException`
- `GlobalScope.launch` anywhere in the codebase
- `CompletableDeferred.await()` without `withTimeout()`
- `@Volatile var` used where `MutableStateFlow` is needed for reactivity (`combine()`/`map()` won't re-evaluate volatile vars)
