---
name: writing-plans
description: "Reads tailor output and writes implementation plans with machine-readable phase ranges for /implement."
user-invocable: true
disable-model-invocation: true
---

# Writing Plans

Use approved tailor output to write an implementation plan. The plan must carry
a machine-readable `Phase Ranges` block so `/implement` can execute by phase
without loading full plan bodies into orchestrator context.

## Prerequisite

`/tailor` must already exist for the spec. If the matching tailor directory is
missing, stop and tell the user to run `/tailor <spec-path>` first.

## Workflow

1. Read the spec.
2. Find the matching tailor directory.
3. Load only the tailor output needed to write the plan.
4. Decide whether the main agent writes directly or whether
   `plan-writer-agent` fragments are warranted.
5. Write the plan.
6. Run the three review sweeps.
7. Apply fixes directly and rerun sweeps up to 3 cycles.
8. Present the final plan summary.

## Writer Strategy

- Write directly for small and medium plans.
- Use `plan-writer-agent` only when the plan is large enough to justify a
  split.
- Split on phase boundaries, not arbitrary file groups.

## Plan Header

Every plan must start with:

```markdown
# <Feature Name> Implementation Plan

> **For Claude:** Use the implement skill (`/implement`) to execute this plan.

**Goal:** <one sentence>
**Spec:** `.claude/specs/YYYY-MM-DD-<name>-spec.md`
**Tailor:** `.claude/tailor/YYYY-MM-DD-<spec-slug>/`
**Blast Radius:** <summary>

## Phase Ranges

| Phase | Name | Start | End |
| --- | --- | --- | --- |
| 1 | <phase name> | <line number> | <line number> |
```

## Phase Range Rules

- Fill the table after the plan body is assembled so line numbers match the
  saved file.
- `Start` and `End` are 1-based line numbers in the final file.
- Each row maps to the matching `## Phase N:` section.
- If review edits change line numbers, update the table before final save.

## Plan Body Rules

- Use real file paths, symbols, and imports from tailor output.
- Keep steps concrete and executable.
- Include enough detail that implementers do not need to guess.
- Include agent routing per step.

## Agent Routing

| File Pattern | Agent |
|-------------|-------|
| `**/termux/**/*.kt`, `assets/scripts/**/*.sh` | `termux-shell-agent` |
| `**/auth/**/*.kt` | `auth-agent` |
| All other Kotlin/Android | Main session |
| Review (any) | `code-review-agent`, `security-review-agent` |
| Performance (any) | `performance-agent` |

## Review Loop

Run these reviewers every cycle:

- `code-review-agent`
- `security-review-agent`
- `completeness-review-agent`

If any reviewer returns findings:

1. consolidate them
2. fix the plan directly in the main conversation
3. rerun all three sweeps

Maximum 3 cycles. If findings still remain, escalate to the user.

## Hard Gates

Do not write the final plan until tailor output is loaded.

Do not present the plan as final until:

1. the header is complete
2. the `Phase Ranges` table is populated
3. the review loop is complete or explicitly escalated

## Save Locations

- Plans: `.claude/plans/YYYY-MM-DD-<feature-name>.md`
- Writer fragments: `.claude/plans/parts/<plan-name>-writer-N.md`
- Dependency analysis: `.claude/dependency_graphs/YYYY-MM-DD-<name>/`
