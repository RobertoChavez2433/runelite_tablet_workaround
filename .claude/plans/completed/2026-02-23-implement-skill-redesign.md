# /implement Skill Redesign — Orchestrator Agent Pattern

**Date**: 2026-02-23
**Status**: APPROVED

## Problem

The current `/implement` skill runs in the main conversation window. As it reads plans, dispatches agents, collects results, and runs review loops, the main window's context fills up. By the time review-fix-verify loops finish, there's little context left for follow-up work.

## Solution

Push ALL orchestration into a sub-agent. The main conversation window becomes a pure supervisor that only spawns orchestrator agents and handles handoffs. Orchestrators checkpoint state to disk after every phase. When an orchestrator hits ~80% context, it returns a structured handoff and the main window spawns a fresh one.

## Architecture

```
Main Window (pure supervisor)
  └─ spawns → Orchestrator Agent (foreground, blocking, Opus)
                ├─ dispatches → Implementer Agents (Sonnet, by file ownership)
                ├─ runs → Build (./gradlew build)
                ├─ dispatches → Fixer Agents (Sonnet, if build/review/lint fails)
                ├─ dispatches → Code Review Agent (Opus, .claude/agents/code-review-agent.md)
                ├─ runs → Lint (./gradlew lint)
                ├─ dispatches → Plan Completeness Verifier (Sonnet)
                ├─ dispatches → Performance Agent (Opus, .claude/agents/performance-agent.md)
                ├─ writes → Checkpoint file after each phase
                └─ returns → HANDOFF at 80% context, DONE when complete, or BLOCKED
```

## Main Window Behavior (The Supervisor)

When the user invokes `/implement`:

1. **Read the plan file** (user provides path)
2. **Check for existing checkpoint** at `.claude/state/implement-checkpoint.json`
   - If exists and matches the same plan: ask user "Resume from checkpoint or start fresh?"
   - If not: initialize a new checkpoint
3. **Spawn orchestrator agent** (foreground, blocking) with:
   - Plan file path
   - Checkpoint file path
   - Project conventions from `.claude/CLAUDE.md`
   - Active defects from `.claude/autoload/_defects.md`
4. **Receive orchestrator result** — one of:
   - **HANDOFF**: Orchestrator hit 80% context. Read updated checkpoint file, spawn fresh orchestrator. Loop continues.
   - **DONE**: All gates passed. Present final summary to user.
   - **BLOCKED**: Issue hit max fix attempts. Present issue to user for manual intervention.
5. **Clean up** — delete checkpoint file after DONE

The main window NEVER reads source files, reviews agent output, or dispatches implementers directly. It is a `while` loop around orchestrator spawns.

## Checkpoint File

Path: `.claude/state/implement-checkpoint.json`

Lightweight, purpose-built. Overwritten by the orchestrator after every phase.

```json
{
  "plan": "plans/2026-02-22-display-and-launch-ux-design.md",
  "phases": [
    {"id": 1, "name": "Install openbox", "status": "done", "files": ["setup-environment.sh"]},
    {"id": 2, "name": "Openbox config", "status": "in_progress", "files": ["openbox-rc.xml"]}
  ],
  "build": "pass",
  "modified_files": ["setup-environment.sh", "openbox-rc.xml"],
  "review": {
    "status": "in_progress",
    "findings": [
      {"severity": "P0", "file": "setup-environment.sh:42", "issue": "Missing error check after apt-get", "fixed": false}
    ]
  },
  "lint": "pending",
  "completeness": "pending",
  "performance": "pending",
  "decisions": [
    "Split agents by layer: shell scripts vs Kotlin vs configs",
    "Used || true for proot calls per defect pattern"
  ],
  "fix_attempts": [
    {"issue": "Missing error check after apt-get", "attempts": 1, "max": 3}
  ],
  "blocked": []
}
```

## Orchestrator Agent Behavior

### On Start
1. Read the checkpoint file
2. Read the plan file
3. Determine current position: which phases are done, which is next
4. If resuming mid-review-cycle, pick up from the last review/fix state

### Implementation Loop (for each remaining phase)
1. **Analyze the phase** — identify files to modify, dependencies on prior phases
2. **Dispatch implementer agent(s)** — Sonnet, by file ownership, each gets:
   - The plan text (relevant phase only)
   - Current contents of their assigned files
   - Project conventions + active defects
3. **Collect results** — agent returns, orchestrator verifies files were modified
4. **Run build** — `./gradlew build` from `runelite-tablet/`
   - If build fails: dispatch fixer agent (Sonnet) with error output. Rebuild. Track attempt count.
   - If 3 build-fix attempts fail: mark phase as BLOCKED, return to main window.
5. **Update checkpoint** — mark phase as done, write checkpoint file

### Quality Gate Loop (after all phases done)

Gates run in order. Each must pass before the next runs.

1. **Build** — `./gradlew build` from `runelite-tablet/`
2. **Lint** — `./gradlew lint` from `runelite-tablet/`
   - If errors: dispatch fixer → re-lint. Max 3 attempts.
3. **Code Review** — dispatch code-review-agent (Opus, read-only) on all modified files
   - Uses full agent definition from `.claude/agents/code-review-agent.md`
   - If P0/P1 found: dispatch fixer (Sonnet) → rebuild → re-review
   - Max 3 fix attempts per finding, then BLOCKED
   - Loop until `QUALITY GATE: PASS`
4. **Plan Completeness** — dispatch verifier agent (Sonnet) that reads the plan and all modified files
   - Confirms every requirement is met
   - If gaps found: dispatch implementer → rebuild → re-verify
   - Loop until `COMPLETION GATE: PASS`
5. **Performance Review** — dispatch performance-agent (Opus, read-only) on all modified files
   - Uses full agent definition from `.claude/agents/performance-agent.md`
   - If P0/P1 found: dispatch fixer (Sonnet) → rebuild → re-review
   - Max 3 fix attempts per finding, then BLOCKED
   - Loop until `PERFORMANCE GATE: PASS`
   - Runs last because most expensive — only on code that already passes all other gates

### Context Management
- After processing each agent's result, the orchestrator extracts only what it needs (pass/fail, findings list, files modified) and discards verbose output
- At ~80% context utilization:
  1. Write final checkpoint state to disk
  2. Return structured handoff report to main window with status `HANDOFF`

### Termination States
- **DONE**: All phases complete + all 5 gates pass → return final summary
- **HANDOFF**: Context at 80% → checkpoint + handoff report
- **BLOCKED**: Issue hit max fix attempts → return issue details for human review

## Agent Dispatch Reference

| Role | subagent_type | model | Writes Code? | Agent Definition |
|------|--------------|-------|-------------|-----------------|
| Orchestrator | general-purpose | opus | No | Skill-defined behavior |
| Implementer | general-purpose | sonnet | Yes | N/A — phase + files + conventions |
| Code Reviewer | general-purpose | opus | No | `.claude/agents/code-review-agent.md` |
| Performance Reviewer | general-purpose | opus | No | `.claude/agents/performance-agent.md` |
| Plan Verifier | general-purpose | sonnet | No | N/A — plan + files checklist |
| Fixer | general-purpose | sonnet | Yes | Specific finding(s) to fix |

**Implementer split**: By file ownership to prevent edit conflicts. Orchestrator analyzes plan's file-change list and groups files into non-overlapping sets.

**Max concurrency**: 3 parallel implementer agents. Sequential for dependent phases.

## Error Handling

| Scenario | Behavior |
|----------|----------|
| Build fails after implementation phase | Dispatch fixer with build error output. Rebuild. Max 3 attempts, then BLOCKED. |
| Code review finds P0/P1 | Dispatch fixer with findings. Rebuild + re-review. Max 3 attempts per finding, then BLOCKED. |
| Lint errors | Dispatch fixer with lint output. Re-lint. Max 3 attempts, then BLOCKED. |
| Performance review P0/P1 | Dispatch fixer. Rebuild + re-review. Max 3 attempts per finding, then BLOCKED. |
| Plan completeness gap | Dispatch implementer for missing requirement. Rebuild + re-verify. Max 3 attempts, then BLOCKED. |
| BLOCKED returned to main window | Present issue to user with full context. User decides: fix manually, skip, or adjust plan. |
| Orchestrator hits 80% context | Write checkpoint, return HANDOFF. Main window spawns fresh orchestrator. |
| Checkpoint file exists on fresh `/implement` | Ask user: resume or start fresh. |
| Implementer agent fails/times out | Orchestrator retries once. If second failure, marks phase BLOCKED. |
| Edit conflict (shouldn't happen) | Orchestrator detects and re-dispatches with corrected file assignments. |

## Final Summary Format

When the orchestrator chain completes with DONE, the main window presents:

```
## Implementation Complete

**Plan**: [plan filename]
**Orchestrator cycles**: N (M handoffs)

### Phases
1. [Phase name] — DONE
2. [Phase name] — DONE
...

### Files Modified
- [file list]

### Quality Gates
- Build: PASS
- Lint: PASS
- Code Review: PASS (N cycles — M findings fixed)
- Plan Completeness: PASS
- Performance Review: PASS

### P2 Nitpicks (for awareness)
- [list]

### Decisions Made
- [list from checkpoint]

Ready to review and commit.
```

The main window does NOT commit or push.

## Project Context (Always Provided to Agents)

- Source: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- Scripts: `runelite-tablet/app/src/main/assets/scripts/`
- Build: `./gradlew build` from `runelite-tablet/`
- Lint: `./gradlew lint` from `runelite-tablet/`
- Conventions: `.claude/CLAUDE.md`
- Prior defects: `.claude/autoload/_defects.md`
- Code review agent: `.claude/agents/code-review-agent.md`
- Performance agent: `.claude/agents/performance-agent.md`
