# Setup Constraints

Per-package hard rules and soft guidelines derived from Solved Problems #8, #9 and setup defect patterns.

## Hard Rules (Reject Proposal If Violated)

1. **`@Volatile var` is NOT reactive** — `combine()`/`map()` on StateFlow won't re-evaluate when a `@Volatile var` changes. Use `MutableStateFlow` for any value that feeds into a derived flow.

2. **Sealed class companion `val` referencing its own subclass objects is null during ART static init** — Always use `by lazy` for sealed class companion properties that reference subclass objects. Direct `val` assignment causes null at runtime.

3. **reconcileWithMarkers ABSENT must clear stateStore** — When `reconcileWithMarkers()` downgrades step status to Pending, it must also call `stateStore.clearCompleted(key)`. Without this, `executeStep()` checks stateStore first and skips the step — it never re-runs.

4. **resetSetup must reset orchestrator state** — `resetSetup()` clears stateStore but must also reset orchestrator's `_permissionPhase`, `_awaitingPermissionCompletion`, and `failedStepIndex`. Add `orchestrator.resetState()` method.

5. **runSetupForHealth must reset setupStarted** — Without resetting `setupStarted.set(false)`, `startSetup()` no-ops on re-entry after a health-triggered reset.

## Soft Guidelines (Discuss Before Proceeding)

- Use `ViewModelProvider.Factory` + `by viewModels{}` delegate for lifecycle safety
- SetupActions callback pattern (not direct Activity reference) to avoid leaks
- Manual DI (constructor injection) wired in Application/Activity
- SetupOrchestrator runs all 7 steps sequentially — no parallel step execution

## Defect Patterns

- stateStore cache not cleared on marker reconciliation ABSENT branch
- orchestrator state not reset on resetSetup
- setupStarted flag not reset on runSetupForHealth

## Related Files

- `defects/_defects-setup.md` — active defect patterns
