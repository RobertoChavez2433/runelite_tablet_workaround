# UI Feature Architecture

## Component Design

```
MainActivity (Activity)
  ├── SetupActions implementation (bind/unbind in onResume/onPause)
  ├── ViewModelProvider.Factory + by viewModels{} delegate
  └── setContent { SetupScreen(viewModel) }

SetupScreen (@Composable)
  ├── val state = viewModel.uiState.collectAsState()
  ├── Column of StepItem composables
  └── Action buttons (retry, reset)

StepItem (@Composable)
  ├── Step label
  ├── Status indicator (Pending/InProgress/Completed/Failed)
  └── Error message (if Failed)

Theme.kt
  └── Material 3 color scheme, typography, shapes
```

## State Flow

```
SetupViewModel.uiState: StateFlow<SetupState>
  ↓ collectAsState()
SetupScreen receives SetupState
  ↓ renders
StepItem for each step in state.steps
```

## Key Design Decisions

- **State hoisting** — all mutable state in ViewModel, composables are stateless functions
- **collectAsState()** — standard pattern for observing StateFlow in Compose
- **No side effects in composition** — all effects use LaunchedEffect/DisposableEffect
- **Single-screen** — no navigation framework needed for 1 screen
- **SetupActions callback** — Activity implements interface, bound in onResume, unbound in onPause

## Recomposition Strategy

- StepItem recomposes only when its individual step state changes
- SetupScreen recomposes when overall SetupState changes
- Use `@Stable`/`@Immutable` on data classes to help compiler skip
