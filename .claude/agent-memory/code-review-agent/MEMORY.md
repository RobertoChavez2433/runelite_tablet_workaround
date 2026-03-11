# Code Review Agent Memory

## Recurring Patterns Found in Reviews

### Coroutine Safety
- CancellationException swallowed in `catch (e: Exception)` blocks — found multiple times across setup and termux packages
- CompletableDeferred.await() without withTimeout() — found in TermuxCommandRunner early versions
- TimeoutCancellationException catch ordered AFTER CancellationException (dead code) — found in SetupViewModel

### State Management
- `@Volatile var` used where `MutableStateFlow` needed — combine()/map() doesn't react to volatile changes
- Sealed class companion `val` null during ART static init — must use `by lazy`
- stateStore not cleared when reconcileWithMarkers downgrades step status

### Resource Cleanup
- OkHttp Response not closed via `.use {}` — connection pool leak
- PackageInstaller session not abandoned in error paths
- APK cache files not deleted after PackageInstaller session opened

### Shell Integration
- Unescaped `"` inside `bash -c "..."` blocks — 16 instances found in launch-runelite.sh
- Missing `set -euo pipefail` in scripts
- Hardcoded paths instead of `$PREFIX`/`$HOME` variables

## Calibration Notes

- This project uses manual DI (no Hilt/Koin) — do not flag as anti-pattern
- Single-screen architecture with no Navigation — this is intentional
- AtomicInteger for execution IDs is accepted (not UUID) — narrow collision window is acceptable for personal use
