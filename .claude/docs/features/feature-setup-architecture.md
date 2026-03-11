# Setup Feature Architecture

## Component Design

```
SetupViewModel
  ├── MutableStateFlow<SetupState> (UI state)
  ├── stateStore (persistence for completed steps)
  ├── setupStarted: AtomicBoolean
  └── SetupOrchestrator
        ├── _permissionPhase, _awaitingPermissionCompletion
        ├── failedStepIndex
        ├── reconcileWithMarkers() → check env vs stored state
        ├── executeStep(index) → run individual step
        └── resetState() → clear all internal flags
```

## Data Flow

```
SetupOrchestrator.runSetup()
  ↓ updates MutableStateFlow<SetupState>
SetupViewModel.uiState (StateFlow<SetupState>)
  ↓ collected by
SetupScreen via collectAsState()
  ↓ renders
StepItem for each SetupStep (status, label, error message)
```

## State Management

| Component | State Type | Purpose |
|-----------|-----------|---------|
| SetupViewModel | MutableStateFlow<SetupState> | UI-facing state |
| stateStore | Persistent key-value | Tracks completed steps across crashes |
| SetupOrchestrator | Internal vars | Permission phase, failed step tracking |
| SetupStep sealed class | Immutable | Step status: Pending, InProgress, Completed, Failed |

## Key Design Decisions

- **MutableStateFlow, not @Volatile** — `@Volatile var` is not reactive; `combine()`/`map()` won't re-evaluate. Use `MutableStateFlow` for any value that feeds derived flows.
- **Sealed class `by lazy` for companion vals** — Direct `val` referencing subclass objects is null during ART static init.
- **stateStore + marker reconciliation** — reconcileWithMarkers ABSENT branch must clear stateStore alongside UI status.
- **resetState() method** — resetSetup must reset orchestrator's internal flags, not just stateStore.
- **SetupActions callback** — Decouples ViewModel from Activity to prevent leaks.

## Marker Reconciliation

```
For each step:
  1. Check marker file/condition on disk
  2. If PRESENT and stateStore says completed → keep
  3. If ABSENT and stateStore says completed → downgrade to Pending + clear stateStore
  4. If PRESENT and stateStore says pending → upgrade to Completed
```
