# Spec Output

How to draft the spec file, run the 7-check self-review, and drive the user
review gate.

## Output Path

```
.claude/specs/YYYY-MM-DD-<slug>-spec.md
```

The skill saves the file. It never commits.

## Shared Spine

Every spec opens with:

```markdown
# <Topic Title>

**Work Type:** <primary type>
**Date:** YYYY-MM-DD
**Spec Author:** Paired conversation (<model name> + user)

---

## Intent

**Problem:** <from Intent gate>
**Who feels it:** <bullets>
**Success criteria (measurable):**
1. <criterion>
2. <criterion>
**Why now:** <paragraph>

---

## Scope

### In scope (v1)
- <bullet>

### Deferred
- <bullet>

### Out of scope
- <bullet>

### Constraints
- <bullet>

### Non-goals
- <bullet>

---

## Vision

**User journey:**
1. <step>

**Key interactions:**
- <bullet>

**Acceptance-by-feel:**
- <bullet>

---
```

## Per-Type Tail Sections

Append the tail matching the work type:

- **New Feature:** Selected Shape, Entry Point & Empty State, Open Questions
- **Feature Add/Mod:** Current Behavior, New Behavior, Change Shape, Files Likely Affected, Open Questions
- **Bug Fix:** Reproduction, Fix Shape, Suspected Locations, Regression Guard, Open Questions
- **Refactor+:** Pain Point, Target Shape, Ambition Level, Blast Radius Budget, Open Questions
- **Security/Documentation:** Selected Shape, Constraints & Invariants, Validation, Open Questions

## Self-Review — 7 Checks

| # | Check | What to look for |
|---|-------|------------------|
| a | Placeholder scan | TBD, TODO, empty bullets, template tokens |
| b | Internal consistency | Sections contradict each other? |
| c | Scope cohesion | One effort or conflation? |
| d | Ambiguity check | Could a requirement be read two ways? |
| e | CLAUDE.md constraint check | Violates documented constraints? |
| f | Traceability | Every bullet traces to a user confirmation? |
| g | Success criteria measurability | Can a human verify without asking "what does that mean"? |

Present findings as a numbered list with per-item reasoning and these verbs:
`approve`, `reject`, `skip`, `edit <n>: <text>`, `add: <finding>`.

## User Review Gate

Always present the saved spec for fresh-eye review:

```
## Spec saved for your review

Path: `.claude/specs/YYYY-MM-DD-<slug>-spec.md`

**Reply:**
- `approved` — proceed
- `fix: <what>` — edit and re-present
- `reopen: <gate>` — snap back
```

## Terminal State

```
Spec approved and saved at `.claude/specs/YYYY-MM-DD-<slug>-spec.md`.

Next step: run `/tailor` to map the codebase against this spec before `/writing-plans`.
```

Do not auto-invoke `/tailor`. The user drives the pipeline.
