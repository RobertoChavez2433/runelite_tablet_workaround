# Shared Constraints

Cross-cutting hard rules and soft guidelines that apply across all packages.

## Hard Rules (Reject Proposal If Violated)

1. **Never swallow CancellationException** — Always re-throw or catch specific exception types. A bare `catch (e: Exception)` that doesn't re-throw `CancellationException` breaks structured concurrency and prevents proper coroutine cancellation.

2. **No GlobalScope** — Use `viewModelScope`, `lifecycleScope`, or a custom `CoroutineScope` with proper parent-child cancellation. `GlobalScope` launches leak and are never cancelled.

3. **Structured concurrency** — Every coroutine must have a parent scope that controls its lifecycle. Child coroutines should be cancelled when their parent scope is cancelled.

4. **OkHttp `response.use {}`** — `Response` implements `Closeable`. Forgetting to close it prevents HTTP connection pool reuse and causes connection leaks.

5. **Manual DI only** — Constructor injection wired in `Application` and `Activity`. No Hilt, Koin, or other DI frameworks. Keep the dependency graph simple and explicit.

## Soft Guidelines (Discuss Before Proceeding)

- `ViewModelProvider.Factory` + `by viewModels{}` delegate for ViewModel creation
- SetupActions callback pattern to decouple ViewModel from Activity (prevents leaks)
- Wrap all blocking I/O in `withContext(Dispatchers.IO)` — even asset reads and PackageInstaller sessions
- Use `@Volatile` for fields accessed across dispatchers (ARM64 torn pointer risk)
- Check `coroutineContext.isActive` in blocking loops for cooperative cancellation
- No `!!` operator — use safe calls, `requireNotNull()`, or sealed class exhaustive matching

## Architecture Principles

- **KISS** — No over-abstraction, no premature optimization
- **DRY** — Extract shared logic, but don't create unnecessary abstraction layers
- **YAGNI** — No features that aren't needed for the current phase

## Related Files

- `rules/coroutine-safety.md` — path-triggered coroutine rules
