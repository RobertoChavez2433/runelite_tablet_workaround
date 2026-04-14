---
name: implement
description: "Executes approved implementation plans with Agent-tool dispatch, per-phase completeness review, and a final multi-reviewer quality gate."
user-invocable: true
disable-model-invocation: true
---

# Implement

Execute an approved plan as a thin orchestrator. The main conversation reads
only plan metadata, dispatches all real work through the Agent tool, and
reports progress back to the user.

## Iron Laws

1. The orchestrator never edits source files.
2. The orchestrator never reads a phase body into its own context.
3. The orchestrator never writes a checkpoint or resume file.
4. The orchestrator never runs build, lint, or git commands.
5. Implementers and fixers run on Sonnet. Reviewers run on Opus.
6. Every dispatched agent begins by reading the correct rules file:
   `references/worker-rules.md` for implementers and fixers,
   `references/reviewer-rules.md` for reviewers.

## Allowed Tools

The main conversation may use only:

- `Read` for the plan header region
- `Grep` to find `## Phase N` headings if the header lacks ranges
- `Agent` for all implementation, review, and fix work

## Plan Intake

1. Accept `/implement <plan-path> [phase-numbers]`.
2. If the user passes a bare filename, resolve it under `.claude/plans/`.
3. Read only the plan header region.
4. Extract:
   - the spec path from `**Spec:**`
   - the phase list
   - per-phase line ranges from the machine-readable header block
5. If the header lacks ranges, infer them from `## Phase N` headings.
6. Present the phase list and ask:

```text
Start implementation? (yes / no / adjust)
```

Do not continue until the user confirms.

## Per-Phase Loop

Run phases strictly in sequence. A phase is not closed until completeness
review returns zero findings at every severity.

### Step 1: Dispatch Implementer

Dispatch a `general-purpose` agent on Sonnet with an inline prompt that
includes:

- the plan path
- the exact phase line range
- the spec path
- the phase name
- instruction to read `references/worker-rules.md` first
- this reply contract:

```text
STATUS: done | failed | blocked
FILES_CREATED:
- ...
FILES_MODIFIED:
- ...
BUILD:
- gradlew build: clean | failed
NOTES:
- ...
```

The implementer owns source edits and build verification. The orchestrator
records the reply only.

### Step 2: Dispatch Completeness Reviewer

Dispatch `completeness-review-agent` on Opus with:

- `mode: per-phase`
- `spec_path`
- `plan_path`
- `plan_line_range`
- `files_in_scope`
- instruction to read `references/reviewer-rules.md` first

If the reviewer returns zero findings, the phase passes.

### Step 3: Fix Findings

If completeness returns any finding, dispatch a narrow `general-purpose` fixer
on Sonnet with:

- instruction to read `references/worker-rules.md` first
- the inline findings list only

Do not send the fixer the full spec or plan body.

### Step 4: Cap And Escalation

The per-phase review/fix loop has a hard cap of 3 cycles.

- Cycle 1: review, then fix if needed
- Cycle 2: review, then fix if needed
- Cycle 3: terminal reviewer pass

If cycle 3 still returns findings, escalate with:

```text
continue / stop / manual fix
```

### Step 5: Per-Phase Status

After a phase closes, print a terse status block with:

- phase name
- review cycle count
- files created count
- files modified count
- build status

## Final Gate

After every requested phase passes, run one final quality sweep.

### Final Review Fan-Out

Dispatch these three reviewers in parallel:

1. `completeness-review-agent` with `mode: final-sweep`
2. `code-review-agent`
3. `security-review-agent`

All final reviewers run on Opus.

### Finding Split

Split final findings into:

- fixer-bound:
  - every completeness finding
  - CRITICAL, HIGH, and MEDIUM code-review findings
  - CRITICAL, HIGH, and MEDIUM security findings
- backlog-bound:
  - LOW code-review findings
  - LOW security findings

Completeness findings are never backlogged.

### Final Fix Loop

If the fixer-bound bucket is non-empty, dispatch a narrow Sonnet fixer with
`references/worker-rules.md` plus the inline findings list, then rerun the full
three-reviewer sweep.

The final gate also has a hard cap of 3 cycles. If cycle 3 still returns
fixer-bound findings, escalate with:

```text
stop / manual fix / accept-as-is and backlog
```

## Final Summary

End with a concise summary that includes:

- the plan path
- per-phase cycle counts
- final-gate reviewer statuses
- final-gate fix cycle count
- the deduped union of every created or modified file

## Reference Files

- `references/worker-rules.md`
- `references/reviewer-rules.md`
- `references/severity-standard.md`
