# Claude System Redesign

**Date**: 2026-02-22
**Status**: APPROVED
**Approach**: Full Hiscores Mirror — adopt battle-tested patterns from Hiscores Tracker project

## Overview

Redesign the `.claude/` directory to add specialized agents, implementation orchestration, systematic debugging, and upgraded session management. Based on patterns proven across 17 sessions and 7 shipped PRs in the Hiscores Tracker project, adapted for this project's unique stack (Android/Kotlin/Compose, Termux IPC, proot, shell scripting, PackageInstaller, Zink GPU).

## 1. Directory Structure

```
.claude/
├── CLAUDE.md                          # Upgraded: conventions, agent/skill tables, gotchas
├── autoload/
│   ├── _state.md                      # Session state (max 5 sessions)
│   └── _defects.md                    # NEW: consolidated active defects (max 7, auto-loaded)
├── agents/
│   ├── code-review-agent.md           # NEW: senior Kotlin/Android code reviewer (Opus)
│   └── performance-agent.md           # NEW: full-stack performance specialist (Opus)
├── skills/
│   ├── brainstorming/
│   │   ├── SKILL.md                   # UPGRADED: iron law, phases, anti-patterns
│   │   └── references/
│   │       ├── question-patterns.md   # NEW: multiple choice templates
│   │       └── design-sections.md     # NEW: Android/Kotlin section templates
│   ├── implement/
│   │   └── SKILL.md                   # NEW: orchestrator (dispatch, review, verify)
│   ├── systematic-debugging/
│   │   ├── SKILL.md                   # NEW: root cause analysis framework
│   │   └── references/
│   │       └── pressure-tests.md      # NEW: rationalization prevention
│   ├── resume-session/
│   │   └── SKILL.md                   # UPGRADED: agent reference table
│   └── end-session/
│       └── SKILL.md                   # UPGRADED: single _defects.md, max 7, categories
├── docs/
│   └── architecture.md                # NEW: app architecture reference
├── state/                             # Existing (kept)
├── defects/                           # DEPRECATED: content migrated to autoload/_defects.md
├── logs/                              # Existing (kept)
├── plans/                             # Existing (kept)
├── research/                          # Existing (kept)
└── memory/                            # Existing (kept)
```

## 2. Code Review Agent

**File**: `.claude/agents/code-review-agent.md`
**Model**: Opus
**Tools**: Read, Grep, Glob (disallowed: Write, Edit, Bash)

Senior Kotlin/Android engineer with deep knowledge of this project's full stack. Read-only — never writes code.

### Review Checklist (10 categories)

1. **Architecture** — Manual DI wiring, package separation (termux/installer/setup/ui), no circular deps, ViewModel Factory pattern
2. **Lifecycle Safety** — SetupActions bind/unbind in onResume/onPause, ViewModel survives config changes, no Activity leaks, proper resource cleanup
3. **Coroutine Safety** — CancellationException not swallowed, structured concurrency, proper dispatcher usage (IO for network/disk, Main for UI), timeout on all async operations
4. **Jetpack Compose** — State hoisting, no side effects in composition, proper `collectAsState()` usage, `@Immutable`/`@Stable` annotations where needed, recomposition efficiency
5. **Termux Integration** — RUN_COMMAND intent correctness, PendingIntent flags, execution ID uniqueness, result service lifecycle, CompletableDeferred timeout
6. **PackageInstaller** — Session cleanup, fsync before commit, signing conflict detection, NeedsUserAction handling, abandoned session cleanup
7. **Shell Script Safety** — `set -euo pipefail`, idempotency, retry-safe, no hardcoded paths, proper quoting, proot compatibility (no FUSE/systemd/mount)
8. **Security** — No hardcoded credentials, intent validation on exported components, APK cache cleanup, permission declarations minimal
9. **Kotlin Patterns** — Sealed classes exhaustive, proper null safety (no `!!`), try-with-resources via `.use {}`, no raw types
10. **KISS/DRY/YAGNI** — No over-abstraction, no premature optimization, no dead code, no unnecessary features

### Anti-Patterns to Flag (8)

- Activity reference held in long-lived object
- CancellationException caught in generic `catch(e: Exception)`
- Blocking call on Main thread
- Missing timeout on CompletableDeferred.await()
- OkHttp `.execute()` without cancellation awareness
- Shell script without `set -e`
- PackageInstaller session not cleaned up on failure
- Hardcoded Termux paths without fallback

### Output Format

```markdown
## Code Review: [File/Feature Name]

### Summary
[1-2 sentences]

### Critical Issues (Must Fix)
1. **[Issue]** at `file:line`
   - Problem: [Description]
   - Fix: [Recommendation]

### Suggestions (Should Consider)
1. **[Suggestion]** at `file:line`
   - Current: [What exists]
   - Better: [Improvement]
   - Why: [Benefit]

### Minor (Nice to Have)
- [Small improvements]

### Positive Observations
- [What's done well]

### KISS/DRY Opportunities
- [Simplification or deduplication]
```

When used by `/implement`, outputs P0 (must fix) / P1 (should fix) / P2 (nitpick) severities. If no P0/P1: `QUALITY GATE: PASS`.

## 3. Performance Agent

**File**: `.claude/agents/performance-agent.md`
**Model**: Opus
**Tools**: Read, Grep, Glob, Bash (disallowed: Write, Edit)

Full-stack performance specialist covering the entire pipeline: Android app → Termux IPC → proot syscall translation → Java rendering.

### Core Analysis Areas (6 categories)

1. **Android App Performance** — Coroutine dispatcher misuse, Compose recomposition waste, memory leaks, APK download efficiency, StateFlow collection overhead
2. **Termux IPC Overhead** — Intent round-trip latency, CompletableDeferred wait times, concurrent command serialization, service startup cost, execution ID map growth
3. **Shell Script Execution** — apt-get update redundancy, sequential vs parallel installs, network retry logic, disk space pre-checks, lock file for concurrent prevention
4. **Proot Syscall Translation** — Syscall-heavy operations, process spawn overhead, file I/O amplification, memory mapping limitations, DNS resolution overhead
5. **Display & Rendering Pipeline** — Termux:X11 startup latency, X11 socket overhead, PulseAudio TCP vs native pipe, RuneLite software rendering FPS, future Zink/Turnip overhead
6. **Resource Lifecycle** — OkHttpClient connection pool, coroutine scope cancellation, PackageInstaller session accumulation, Termux process cleanup, APK cache management

### Known Performance Concerns

| Issue | Severity | Location | Impact |
|-------|----------|----------|--------|
| OkHttp `.execute()` blocks IO dispatcher | MEDIUM | ApkDownloader | Not cancellation-aware |
| No retry for transient GitHub API failures | MEDIUM | ApkDownloader | 502/503 kills setup flow |
| `apt-get update` always re-runs on retry | LOW | setup-environment.sh | ~30s wasted per retry |
| Hardcoded `sleep 2` for X11 startup | LOW | launch-runelite.sh | May be insufficient/excessive |
| No disk space check before downloads | MEDIUM | setup-environment.sh | Silent failure on full storage |
| pendingResults map not cleaned on timeout | LOW | TermuxResultService | Minor memory leak |

### Output Format

```markdown
## Performance Analysis: [Feature/File]

### Summary
[1-2 sentences]

### Critical Issues (Blocking)
1. **[Issue]** at `file:line`
   - Impact: [Measured or estimated]
   - Fix: [Specific recommendation]
   - Priority: P0/P1/P2

### Optimization Opportunities
1. **[Opportunity]** at `file:line`
   - Current: [What exists]
   - Proposed: [Improvement]
   - Expected gain: [Estimate]

### Benchmarks Needed
- [What to measure and how]

### Resource Lifecycle Assessment
| Resource | Created | Cleaned Up? | Risk |
|----------|---------|-------------|------|
| [resource] | [where] | [Y/N] | [impact] |
```

## 4. The /implement Skill

**File**: `.claude/skills/implement/SKILL.md`
**Iron Law**: `NEVER use Edit or Write tools — only Read, Glob, Grep, Bash (read-only), Task`

Orchestrator that dispatches agents for implementation. Never writes code itself.

### Four-Step Workflow

#### Step 0: Load the Plan
- Read plan file
- Identify all implementation phases
- Glob/Grep to find source files each phase touches
- Present phase list before starting

#### Step 1: Implementation Phases
- Phases with dependencies: sequential
- Independent phases: parallel (max 3 concurrent)
- Each implementer (Sonnet, general-purpose) receives: full plan, phase assignment, current source files, conventions from CLAUDE.md, active defects from `_defects.md`
- Milestone report after each phase (files modified, build status)

#### Step 2: Code Quality Review Loop
- Trigger: all phases complete + build passes
- Reviewer: code-review-agent (Opus)
- Auto-fix loop: P0/P1 found → Sonnet fixer → rebuild → re-review → repeat until QUALITY GATE: PASS
- P2 nitpicks collected for report, don't block

#### Step 3: Plan Completion Review Loop
- Trigger: code quality gate passed
- Reviewer: fresh Sonnet general-purpose agent
- Three tasks: checklist verification, build verification, functional spot-check
- Auto-fix loop: MISSING/MISMATCH → Sonnet fixer → re-verify → repeat until COMPLETION GATE: PASS

#### Step 4: Final Summary
- Phases complete, files modified, review cycles, P2 nitpicks, build status
- "Ready to review and commit."
- Orchestrator does NOT commit/push

### Agent Type Reference

| Role | subagent_type | model | Writes Code? |
|------|--------------|-------|-------------|
| Implementer | general-purpose | sonnet | Yes |
| Code Quality Reviewer | code-review-agent | opus | No |
| Completion Reviewer | general-purpose | sonnet | No |
| Fixer | general-purpose | sonnet | Yes |

### Project Context Always Applied

- Source: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- Build: `./gradlew build` from `runelite-tablet/`
- Conventions: `.claude/CLAUDE.md`
- Prior defects: `.claude/autoload/_defects.md`

## 5. The /systematic-debugging Skill

**File**: `.claude/skills/systematic-debugging/SKILL.md`
**Iron Law**: `NO FIXES WITHOUT ROOT CAUSE INVESTIGATION FIRST.`

### Four Phases

1. **Root Cause Investigation** — Read the error, reproduce, isolate, timeline
2. **Pattern Analysis** — Find working example, compare differences, check timing, validate assumptions
3. **Hypothesis Testing** — ONE hypothesis per test. Revert failed changes. Log results.
4. **Implementation** — Targeted fix, verify, check regressions, log to `_defects.md`

### Stop Conditions

- 3+ failed fix attempts → likely architectural
- Fix requires changing 5+ files → scope too broad
- Can't explain root cause → go back to Phase 1
- "Fix" just suppresses symptoms → haven't found root cause

### Rationalization Prevention

| If You Think... | Stop And... |
|-----------------|-------------|
| "Let me just try this quick fix" | Form a hypothesis first |
| "I'll add a retry and see if it helps" | Find the root cause |
| "It works when I test manually" | Reproduce in failing environment |
| "I've been on this too long, just ship it" | Take a break, come back fresh |
| "The error is in Termux, not our code" | Verify with isolated test |

### Project-Specific Debug Checklist

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

### Defect Categories

- `[COROUTINE]` — CancellationException, dispatcher, timeout, structured concurrency
- `[ANDROID]` — Activity lifecycle, ViewModel, permissions, config changes
- `[COMPOSE]` — Recomposition, state, side effects
- `[TERMUX]` — RUN_COMMAND intent, result service, permissions
- `[INSTALLER]` — PackageInstaller sessions, signing, downloads
- `[SHELL]` — Script errors, proot compatibility, idempotency
- `[SECURITY]` — Intent validation, credential handling, permissions

## 6. Upgraded Existing Skills

### Brainstorming (upgraded)

- Add iron law: `ONE QUESTION AT A TIME. PREFER MULTIPLE CHOICE.`
- Add three phases: Understanding → Exploring → Presenting
- Add anti-patterns section (question dump, open-ended only, assume requirements, skip to solution)
- Add `references/question-patterns.md` — templates for scope, priority, constraint, trade-off questions
- Add `references/design-sections.md` — Android/Kotlin section templates: Overview, Data Model, User Flow, UI Components, App Architecture, External Integration, Edge Cases

### Resume-Session (upgraded)

- Add agent reference table:
  | Domain | Agent |
  |--------|-------|
  | Code Quality | `code-review-agent` |
  | Performance | `performance-agent` |
  | Design | `/brainstorming` |
  | Debugging | `/systematic-debugging` |
  | Implementation | `/implement` |
- Reference `_defects.md` as auto-loaded
- Add on-demand reference list

### End-Session (upgraded)

- Defects go to single `autoload/_defects.md` (auto-loaded) instead of per-feature files
- Max 7 defects, oldest rotates to `logs/defects-archive.md`
- New defects added at TOP of Active Patterns
- Required categories: [COROUTINE], [ANDROID], [COMPOSE], [TERMUX], [INSTALLER], [SHELL], [SECURITY]
- Standardized format: `### [CATEGORY] YYYY-MM-DD: Title` + Pattern, Prevention, Ref

## 7. CLAUDE.md Upgrade

Major expansion with:

- **Quick references**: `@.claude/autoload/_defects.md`, `@.claude/docs/architecture.md`
- **Source file map**: All 15 Kotlin files + 2 shell scripts organized by package
- **Build command**: `./gradlew build` from `runelite-tablet/`
- **Coroutine safety rules table**: Which operations use which dispatchers
- **Agent & skill routing tables**: When to use each agent/skill
- **Common gotchas**: Termux, PackageInstaller, proot, X11, GitHub API quirks
- **Archives listed as on-demand** (DO NOT AUTO-LOAD)

## 8. Architecture Documentation

**File**: `.claude/docs/architecture.md`

- Component diagram (App → MainActivity → SetupScreen → ViewModel → Orchestrator → Termux/Installer/Scripts)
- Data flow (StateFlow → collectAsState → StepItem recomposition)
- 7 setup steps mapped to implementation classes
- Async pattern (CompletableDeferred + PendingIntent + withTimeout)
- Threading model

## 9. Migration

### Files to Create (9 new)
1. `.claude/agents/code-review-agent.md`
2. `.claude/agents/performance-agent.md`
3. `.claude/skills/implement/SKILL.md`
4. `.claude/skills/systematic-debugging/SKILL.md`
5. `.claude/skills/systematic-debugging/references/pressure-tests.md`
6. `.claude/skills/brainstorming/references/question-patterns.md`
7. `.claude/skills/brainstorming/references/design-sections.md`
8. `.claude/docs/architecture.md`
9. `.claude/autoload/_defects.md`

### Files to Modify (4)
10. `.claude/CLAUDE.md` — major upgrade
11. `.claude/skills/brainstorming/SKILL.md` — add iron law, phases, references
12. `.claude/skills/resume-session/SKILL.md` — add agent table
13. `.claude/skills/end-session/SKILL.md` — switch to _defects.md, max 7

### Files to Remove (1)
14. `.claude/defects/_defects-slice1-setup.md` — content migrated to `autoload/_defects.md`

### Commit
Single commit: `Add agents, skills, and project conventions for .claude system`
