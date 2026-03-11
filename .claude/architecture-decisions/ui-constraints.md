# UI Constraints

Per-package hard rules and soft guidelines derived from Compose conventions and Solved Problem #10.

## Hard Rules (Reject Proposal If Violated)

1. **Catch TimeoutCancellationException BEFORE CancellationException** — `TimeoutCancellationException` is a subclass of `CancellationException`. Catching `CancellationException` first makes the `TimeoutCancellationException` catch unreachable (dead code). This applies in any coroutine-aware UI code.

2. **No side effects in @Composable functions** — Never call suspend functions, launch coroutines, or perform I/O during composition. Use `LaunchedEffect`, `DisposableEffect`, or `SideEffect` instead.

3. **State hoisting to ViewModel** — All mutable state lives in ViewModel as `MutableStateFlow`. Composables receive state as parameters and emit events upward.

4. **Single-screen architecture** — This app uses state-driven content switching, not Jetpack Navigation. Do not introduce navigation components.

## Soft Guidelines (Discuss Before Proceeding)

- Use `@Immutable` and `@Stable` annotations on data classes for recomposition efficiency
- Use `remember` and `derivedStateOf` for expensive computations
- Minimize recomposition scope by extracting small composable functions
- Material 3 theme defined in `Theme.kt` — extend, don't replace

## Defect Patterns

- No active UI defects currently tracked

## Related Files

- `rules/compose-ui.md` — path-triggered rule file
- `defects/_defects-ui.md` — active defect patterns
