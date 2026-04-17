# Runelite for Tablet

Tablet-optimized OSRS client based on RuneLite. Android app orchestrates Termux, proot, X11, and shell scripts to run RuneLite on tablets.

## Build

```bash
cd runelite-tablet && ./gradlew build
```

Source root: `runelite-tablet/app/src/main/java/com/runelitetablet/`

## Working Rules

- **DI**: Manual constructor injection, wired in Application/Activity
- **Navigation**: Single-screen, state-driven content switching
- **ViewModel**: `ViewModelProvider.Factory` + `by viewModels{}` delegate
- **Lifecycle**: SetupActions callback with bind/unbind in onResume/onPause
- **Execution IDs**: AtomicInteger for Termux IDs (not nanoTime)
- Path-triggered rules in `rules/` auto-load when editing matching files
- Hard constraints in `architecture-decisions/` loaded by agents on demand
- GitHub issues are the defect/work-item system of record. Do not create `.claude/defects/*`. Follow `.claude/specs/2026-04-16-issue-convention-spec.md` for title/body/labels and `.claude/specs/2026-04-16-commit-convention-spec.md` for commit grammar.

## When To Use

| Situation | Use |
|-----------|-----|
| New feature or behavior change | `/brainstorming` -> `/tailor` -> `/writing-plans` -> `/implement` |
| Spec/plan quality check | `/adversarial-review` |
| .claude/ health check | `/audit-docs` |
| Bug or unexpected behavior | `/systematic-debugging` |
| Code quality | `code-review-agent` |
| Performance | `performance-agent` |
| Security or credentials | `security-review-agent` |
| Termux/shell work | `termux-shell-agent` |
| Auth/OAuth work | `auth-agent` |
| Session start / end | `/resume-session` / `/end-session` |

## On-Demand References

- Architecture: `docs/architecture.md`
- Feature docs: `docs/features/`
- State: `autoload/_state.md` (hot), `state/*.json` (cold)
- Plans: `plans/`, Specs: `specs/`, Tailor: `tailor/`

