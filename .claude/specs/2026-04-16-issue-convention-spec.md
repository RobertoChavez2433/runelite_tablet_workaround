# Tablite Issue Convention

**Date**: 2026-04-16 | **Status**: Active | **Mirrors**: `2026-04-16-commit-convention-spec.md`

## Why

Issues are the defect / work-item system of record. Local `.claude/defects/*` is retired. Every tracked problem, blocker, or task decision lives as a GitHub issue so reasoning is searchable across sessions.

The structure mirrors the commit convention so humans and agents read one grammar in both places.

## Title

```
<type>(<scope>): <subject — imperative mood, ≤72 chars>
```

Same types and scopes as the commit convention:

- **Types** (9): `feat` `fix` `refactor` `perf` `test` `docs` `chore` `ci` `build`
- **Scopes** — `scripts/git/valid-scopes.txt`. Unknown scope = reject at helper level.
- **Scope required** for `feat` / `fix` / `refactor` / `perf`. Optional otherwise.

A fix for a live defect is `fix(scope):`. A planned refactor is `refactor(scope):`. An investigation that may produce no code is `chore(scope):`. Blockers without a concrete fix are `chore(scope):` with a `Blocker:` trailer.

## Body (narrative)

Mandatory sections for any opened issue:

- **Problem:** what is broken / what is the forcing function
- **Decision:** the proposed or leading approach (nullable for chores/investigations — write "Not yet decided" if so)
- **Tradeoff:** what is being accepted or rejected
- **Evidence:** repro steps, log excerpts, file paths, device capture — the proof the problem exists

Rules: ≥40 non-whitespace chars across the four sections. Plain Markdown — no HTML. Wrap at 100 cols for readability.

## Trailers

Block at the end of the body, blank line before. `Key: value` only.

- **`Reason:`** — one-line forcing function. **Required** on every opened issue.
- `Refs:` — `<path>:<line>`, `#<issue>`, or commit SHA.
- `Follow-up:` — known remaining work.
- `Blocker:` — one line; presence elevates priority, label becomes `type:blocker`.
- `Resolved-by:` — commit SHA or PR ref. Added on close. Replaces "linked PR" auto-magic.

## Labels

Three axes, always applied together.

**Type** (one of):
- `type:feat` `type:fix` `type:refactor` `type:perf` `type:test` `type:docs` `type:chore` `type:ci` `type:build` `type:blocker` `type:security`

**Scope** (one, matching `valid-scopes.txt`):
- `scope:auth` `scope:termux` `scope:native` `scope:presentation` `scope:setup` `scope:logging` `scope:ui` `scope:installer` `scope:perf` `scope:scripts` `scope:docs` `scope:deps` `scope:tests` `scope:ci` `scope:tooling`

**Priority** (one of):
- `p0` `p1` `p2` `p3`

Optional:
- `historical` — issue migrated from pre-convention defect file; closed immediately.
- `needs-repro` — evidence block is insufficient.

## Lifecycle

1. **Open** — `tools/create-issue.ps1` creates the issue with title, body, trailers, and labels. Helper validates type / scope / priority against allowlists.
2. **Work** — commits that touch the issue reference it via `Refs: #N` trailer. No forced keyword linking.
3. **Close** — either:
   - Human-triggered: `gh issue close <n> --comment "Resolved in <sha>. Reason: <short>"` — the closing comment must carry a `Resolved-by:` trailer referencing a commit or PR.
   - Superseded: `type:chore` issue closed with `Closed as: duplicate-of #N` body edit.

Do not auto-close via commit-message keywords (`Fixes #N`). Commits link via `Refs:`; closing is a human act that records the `Resolved-by:` decision.

## Historical migration

Pre-existing defects from `.claude/defects/*` are ported as closed issues with the `historical` label, body preserved, subject remapped to `<type>(<scope>): <subject>`. This makes the record searchable without cluttering open queues.

Active-but-still-reproducible defects become **open** issues of `type:fix` / `type:refactor` with a migrated `Problem:` / `Decision:` body.

## Example — open defect

```
Title: fix(setup): reconcile ABSENT status must clear stateStore

Body:
Problem: SetupOrchestrator.reconcileWithMarkers() downgrades step status to
Pending when the marker is ABSENT, but does not clear the stateStore
`isCompleted` flag. executeStep() checks stateStore first and no-ops, so the
step never actually re-runs.

Decision: In the ABSENT branch, call stateStore.clearCompleted(key) alongside
updateStepStatus(index, Pending).

Tradeoff: Tiny extra write per reconcile; negligible vs the alternative of
silent step skipping.

Evidence: SetupOrchestrator.kt:reconcileWithMarkers currently calls only
updateStepStatus; no stateStore write is present in that branch.

Reason: affected step silently skips after reconcile, breaking setup recovery
Refs: runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupOrchestrator.kt

Labels: type:fix, scope:setup, p1
```

## Example — historical

```
Title: chore(scripts): MESA_GLSL_VERSION_OVERRIDE was missing for VirGL plugin

Body: (preserved from defect archive)

Resolved-by: launch-runelite.sh sets MESA_GLSL_VERSION_OVERRIDE="430" since 2026-03-09

Labels: type:chore, scope:scripts, p3, historical
State: closed
```

## Enforcement

- `tools/create-issue.ps1` rejects bad type / scope / priority at param-validate time.
- Body format is not hook-enforced (GitHub doesn't run local hooks on web edits); the helper refuses to send with a body under 40 non-whitespace chars or missing `Reason:` trailer.
- Commits that claim to resolve an issue must include `Refs: #N` in the commit body — reviewed by PR gate, not a hard hook.
