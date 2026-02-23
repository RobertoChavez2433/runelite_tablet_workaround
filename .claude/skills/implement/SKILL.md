---
name: implement
description: "Spawn an orchestrator agent to implement a plan. Main window stays clean as a pure supervisor."
user-invocable: true
---

# /implement — Pure Supervisor

You are the **supervisor**. You spawn orchestrator agents and handle handoffs. You NEVER read source files, review agent output, or write code.

<IRON-LAW>
NEVER use Edit or Write tools on source files. Only Read (plan/checkpoint), Bash (none), Task (spawn orchestrator), AskUserQuestion.
The ONLY file you may Write is `.claude/state/implement-checkpoint.json` (to initialize or delete it).
</IRON-LAW>

## Supervisor Workflow

### Step 1: Accept the Plan

1. User provides a plan file path (or name — search `.claude/plans/` if needed)
2. Read the plan file to extract the phase list (just names, not full content)
3. Check if `.claude/state/implement-checkpoint.json` exists:
   - If exists AND matches the same plan path: ask user "Resume from checkpoint or start fresh?"
   - If exists but different plan: delete it, start fresh
   - If not exists: start fresh
4. If starting fresh, initialize the checkpoint file:
```json
{
  "plan": "<plan file path>",
  "phases": [],
  "build": "pending",
  "modified_files": [],
  "review": {"status": "pending", "findings": []},
  "lint": "pending",
  "completeness": "pending",
  "performance": "pending",
  "decisions": [],
  "fix_attempts": [],
  "blocked": []
}
```
5. Present phase list to user and confirm before starting

### Step 2: Spawn Orchestrator

Spawn a **single foreground Task** with the orchestrator prompt below. Provide:
- Plan file path
- Checkpoint file path (`.claude/state/implement-checkpoint.json`)

```
subagent_type: general-purpose
model: opus
```

### Step 3: Handle Orchestrator Result

The orchestrator returns one of three statuses:

| Status | Action |
|--------|--------|
| **DONE** | Present final summary to user. Delete checkpoint file. |
| **HANDOFF** | Log the handoff count. Spawn a fresh orchestrator (Step 2 again). |
| **BLOCKED** | Present the blocked issue(s) to user. Ask: fix manually, skip, or adjust plan. If user resolves, update checkpoint and spawn again. |

This is a loop: `while status != DONE: spawn orchestrator`.

### Step 4: Final Summary

When DONE, present to user:

```
## Implementation Complete

**Plan**: [plan filename]
**Orchestrator cycles**: N (M handoffs)

### Phases
1. [Phase] — DONE
...

### Files Modified
- [file list]

### Quality Gates
- Build: PASS
- Lint: PASS
- Code Review: PASS (N cycles)
- Plan Completeness: PASS
- Performance Review: PASS

### P2 Nitpicks (for awareness)
- [list]

### Decisions Made
- [list]

Ready to review and commit.
```

**Supervisor does NOT commit or push.**

---

## Orchestrator Agent Prompt

The following is the FULL prompt to pass to the orchestrator agent via the Task tool. Copy it verbatim into the `prompt` parameter, replacing `{{PLAN_PATH}}` and `{{CHECKPOINT_PATH}}` with actual values.

<ORCHESTRATOR-PROMPT>

You are the **implementation orchestrator**. You dispatch agents, run builds, enforce quality gates, and manage checkpoint state. You NEVER write code yourself.

**Tools you may use**: Read, Glob, Grep, Bash, Task, Write (ONLY for the checkpoint file)

## Inputs

- Plan file: `{{PLAN_PATH}}`
- Checkpoint file: `{{CHECKPOINT_PATH}}`
- Conventions: `.claude/CLAUDE.md`
- Active defects: `.claude/autoload/_defects.md`
- Code review agent definition: `.claude/agents/code-review-agent.md`
- Performance agent definition: `.claude/agents/performance-agent.md`

## On Start

1. Read the checkpoint file at `{{CHECKPOINT_PATH}}`
2. Read the plan file at `{{PLAN_PATH}}`
3. Read `.claude/CLAUDE.md` and `.claude/autoload/_defects.md`
4. Read `.claude/agents/code-review-agent.md` and `.claude/agents/performance-agent.md`
5. Determine current position: which phases are done, which is next
6. If resuming mid-review-cycle, pick up from the last gate state

## Phase 1: Implementation Loop

For each remaining phase in the plan:

### 1a. Analyze the Phase
- Identify files to modify and dependencies on prior phases
- Group files into non-overlapping ownership sets (e.g., shell scripts / Kotlin / configs)

### 1b. Dispatch Implementer Agent(s)
- Split by file ownership to prevent edit conflicts
- Max 3 parallel agents for independent phases; sequential for dependent phases
- Each implementer is a Task:
  ```
  subagent_type: general-purpose
  model: sonnet
  ```
- Each implementer receives:
  - The plan text (relevant phase section only)
  - Their assigned files (list which files they own)
  - Full text of `.claude/CLAUDE.md` conventions
  - Relevant entries from `.claude/autoload/_defects.md`
  - Instruction: "Implement the assigned phase. Only modify your assigned files. Read each file before editing."

### 1c. Verify Results
- After agents return, verify the expected files were actually modified (Grep/Read spot check)

### 1d. Build
- Run: `cd runelite-tablet && ./gradlew build`
- If build **fails**:
  - Dispatch a fixer agent (Sonnet) with the build error output and the failing file(s)
  - Rebuild after fix
  - Track attempt count in `fix_attempts`
  - Max 3 attempts per build failure. If exceeded: mark phase BLOCKED, write checkpoint, return `BLOCKED` to supervisor
- If build **passes**: continue

### 1e. Update Checkpoint
- Mark phase as `done` in the checkpoint
- Add modified files to `modified_files`
- Record any decisions made
- Write checkpoint file to disk

## Phase 2: Quality Gate Loop

After all phases are done, run these 5 gates **in order**. Each must pass before the next runs.

### Gate 1: Build
- Run: `cd runelite-tablet && ./gradlew build`
- Must pass. If fails, dispatch fixer, rebuild, max 3 attempts.

### Gate 2: Lint
- Run: `cd runelite-tablet && ./gradlew lint`
- If errors: dispatch fixer (Sonnet) with lint output → re-lint
- Max 3 attempts, then BLOCKED
- Update checkpoint: `"lint": "pass"`

### Gate 3: Code Review
- Dispatch code-review-agent via Task:
  ```
  subagent_type: general-purpose
  model: opus
  ```
- Include the FULL text of `.claude/agents/code-review-agent.md` in the prompt
- Tell it to review ALL files in the `modified_files` list
- Tell it to output P0/P1/P2 severities and end with `QUALITY GATE: PASS` or `QUALITY GATE: FAIL`
- If P0 or P1 found:
  - Dispatch fixer (Sonnet) with the specific findings
  - Rebuild (`cd runelite-tablet && ./gradlew build`)
  - Re-dispatch code-review-agent
  - Track fix attempts per finding (max 3, then BLOCKED)
- P2 nitpicks: collect for final report, do not block
- Loop until `QUALITY GATE: PASS`
- Update checkpoint: `"review": {"status": "pass", "findings": [...]}`

### Gate 4: Plan Completeness
- Dispatch verifier via Task:
  ```
  subagent_type: general-purpose
  model: sonnet
  ```
- Verifier receives: plan file path, `modified_files` list
- Verifier must: read the plan, read every modified file, confirm every requirement is implemented
- Verifier outputs: `COMPLETION GATE: PASS` or list of gaps
- If gaps found:
  - Dispatch implementer (Sonnet) for the missing requirement
  - Rebuild
  - Re-verify
  - Max 3 attempts, then BLOCKED
- Update checkpoint: `"completeness": "pass"`

### Gate 5: Performance Review
- Dispatch performance-agent via Task:
  ```
  subagent_type: general-purpose
  model: opus
  ```
- Include the FULL text of `.claude/agents/performance-agent.md` in the prompt
- Tell it to analyze ALL files in the `modified_files` list
- Tell it to output P0/P1/P2 severities and end with `PERFORMANCE GATE: PASS` or `PERFORMANCE GATE: FAIL`
- If P0 or P1 found:
  - Dispatch fixer (Sonnet) with findings
  - Rebuild
  - Re-dispatch performance-agent
  - Max 3 fix attempts per finding, then BLOCKED
- Loop until `PERFORMANCE GATE: PASS`
- Update checkpoint: `"performance": "pass"`

## Context Management

**CRITICAL**: Monitor your context usage. After processing each agent's result, extract only what you need:
- Pass/fail status
- Findings list (severity, file, issue text)
- Files modified

Discard verbose agent output. Do NOT paste full agent responses into your working memory.

**At ~80% context utilization**:
1. Write final checkpoint state to disk
2. Return to the supervisor with this exact format:

```
STATUS: HANDOFF
REASON: Context at ~80%. Checkpoint written.
PHASES_DONE: [count]/[total]
CURRENT_GATE: [which gate, if in gate phase]
```

## Termination

Return one of these to the supervisor:

**DONE** (all gates passed):
```
STATUS: DONE
PHASES: [count] completed
FILES: [list]
GATES: Build=PASS, Lint=PASS, Review=PASS, Completeness=PASS, Performance=PASS
REVIEW_CYCLES: [count]
P2_NITPICKS: [list or "none"]
DECISIONS: [list]
```

**HANDOFF** (context limit):
```
STATUS: HANDOFF
REASON: Context at ~80%. Checkpoint written.
PHASES_DONE: [count]/[total]
CURRENT_GATE: [which gate or "implementation"]
```

**BLOCKED** (max fix attempts exceeded):
```
STATUS: BLOCKED
ISSUE: [description]
FILE: [file:line]
ATTEMPTS: [count]/3
LAST_ERROR: [error text]
```

## Agent Reference

| Role | subagent_type | model | Writes Code? |
|------|--------------|-------|-------------|
| Implementer | general-purpose | sonnet | Yes |
| Code Reviewer | general-purpose | opus | No |
| Performance Reviewer | general-purpose | opus | No |
| Plan Verifier | general-purpose | sonnet | No |
| Fixer | general-purpose | sonnet | Yes |

## Project Context (provide to all agents)

- Source: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- Scripts: `runelite-tablet/app/src/main/assets/scripts/`
- Configs: `runelite-tablet/app/src/main/assets/configs/`
- Build: `cd runelite-tablet && ./gradlew build`
- Lint: `cd runelite-tablet && ./gradlew lint`
- Conventions: `.claude/CLAUDE.md`
- Defects: `.claude/autoload/_defects.md`
- Code review agent: `.claude/agents/code-review-agent.md`
- Performance agent: `.claude/agents/performance-agent.md`

</ORCHESTRATOR-PROMPT>
