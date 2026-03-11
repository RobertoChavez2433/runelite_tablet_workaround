---
paths:
  - "**/ui/**/*.kt"
---

# Compose UI Rules

## Hard Rules

1. **State hoisting** — lift state to ViewModel, pass down to composables as parameters
2. **`collectAsState()` for StateFlow** — use `stateFlow.collectAsState()` to observe ViewModel state in Compose
3. **No side effects in composition** — never call suspend functions, launch coroutines, or perform I/O during composition; use `LaunchedEffect`, `DisposableEffect`, or `SideEffect` instead
4. **Catch TimeoutCancellationException BEFORE CancellationException** — this applies in any coroutine-aware UI code; wrong order = dead catch block

## Soft Guidelines

- Use `@Immutable` and `@Stable` annotations on data classes passed to composables to help the Compose compiler skip unnecessary recompositions
- Minimize recomposition scope — extract composables into small functions so only affected parts recompose
- Use `remember` and `derivedStateOf` for expensive computations
- Navigation is single-screen, state-driven content switching (no Jetpack Navigation)

## Architecture

- **Single-screen app**: `SetupScreen` is the main Compose screen
- **Material 3 theme**: defined in `Theme.kt`
- **StepItem**: individual step row component rendered per `SetupStep`
- ViewModel exposes `StateFlow<SetupState>` collected by `SetupScreen`

## Anti-Patterns

- Direct Activity references in composables
- `mutableStateOf()` in ViewModel instead of `MutableStateFlow`
- Side effects directly in `@Composable` function body
- Heavy computation without `remember` or `derivedStateOf`
