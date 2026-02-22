---
name: systematic-debugging
description: "Root cause analysis framework. Prevents shotgun debugging by requiring investigation before fixes."
user-invocable: true
---

# /systematic-debugging — Root Cause Analysis

<IRON-LAW>
NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST.
</IRON-LAW>

## Four Phases

### Phase 1: Root Cause Investigation

1. **Read the error** — exact message, stack trace, logs
2. **Reproduce** — confirm it happens consistently and identify trigger conditions
3. **Isolate** — narrow to specific file, function, line
4. **Timeline** — when did it start? What changed?

### Phase 2: Pattern Analysis

1. **Find working example** — is there similar code that works?
2. **Compare differences** — what's different between working and broken?
3. **Check timing** — race condition? Ordering dependency?
4. **Validate assumptions** — are inputs what you expect?

### Phase 3: Hypothesis Testing

- **ONE hypothesis per test** — never test multiple theories at once
- **Revert failed changes** — don't accumulate speculative fixes
- **Log results** — what was tested, what was observed, what was learned

### Phase 4: Implementation

1. **Targeted fix** — minimal change that addresses root cause
2. **Verify** — confirm the original error is gone
3. **Check regressions** — did the fix break anything else?
4. **Log to `_defects.md`** — add pattern for future prevention

## Stop Conditions

- 3+ failed fix attempts -> likely architectural, step back
- Fix requires changing 5+ files -> scope too broad, reassess
- Can't explain root cause -> go back to Phase 1
- "Fix" just suppresses symptoms -> haven't found root cause

## Rationalization Prevention

| If You Think... | Stop And... |
|-----------------|-------------|
| "Let me just try this quick fix" | Form a hypothesis first |
| "I'll add a retry and see if it helps" | Find the root cause |
| "It works when I test manually" | Reproduce in failing environment |
| "I've been on this too long, just ship it" | Take a break, come back fresh |
| "The error is in Termux, not our code" | Verify with isolated test |

See `references/pressure-tests.md` for more rationalization traps.

## Project-Specific Debug Checklist

| Symptom | Likely Cause | Check |
|---------|-------------|-------|
| Setup hangs on a step | CompletableDeferred never completed | Result service callback? ID match? |
| APK install fails silently | PackageInstaller session error | InstallResultReceiver status? Signing? |
| Coroutine keeps running | CancellationException swallowed | Generic catch without re-throw? |
| Shell script fails partway | Missing set -e or unquoted var | Error handling, variable expansion |
| X11 display not connecting | Termux:X11 not started | sleep 2 enough? DISPLAY=:0? |
| Compose UI not updating | StateFlow issue | collectAsState()? value assignment? |
| Termux permission denied | RUN_COMMAND not granted | allow-external-apps set? |
| APK download 404 | GitHub API change | Release API response? Regex? |

## Defect Categories

Use these when logging to `.claude/autoload/_defects.md`:

- `[COROUTINE]` — CancellationException, dispatcher, timeout, structured concurrency
- `[ANDROID]` — Activity lifecycle, ViewModel, permissions, config changes
- `[COMPOSE]` — Recomposition, state, side effects
- `[TERMUX]` — RUN_COMMAND intent, result service, permissions
- `[INSTALLER]` — PackageInstaller sessions, signing, downloads
- `[SHELL]` — Script errors, proot compatibility, idempotency
- `[SECURITY]` — Intent validation, credential handling, permissions
