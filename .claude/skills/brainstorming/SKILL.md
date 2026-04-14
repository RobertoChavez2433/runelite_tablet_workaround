---
name: brainstorming
description: "Use for large, ambiguous, or cross-cutting work that needs intent, scope, and success criteria locked before planning. Produces an approved spec for tailor and writing-plans."
user-invocable: true
disable-model-invocation: true
---

# Brainstorming

Turn an idea into an approved spec at `.claude/specs/YYYY-MM-DD-<slug>-spec.md`.
This skill locks user intent first. Tailor maps the codebase later, and
writing-plans turns the approved spec into execution steps.

## When To Use

Use this skill when the change is:

- large or cross-cutting
- ambiguous or under-specified
- product-shaping
- risky enough that success criteria must be locked before planning

Skip it for small, clear, low-risk changes when the user already gave a direct,
implementation-ready request.

## Hard Gate

For work that does need brainstorming, do not plan or implement until the spec
is written, self-reviewed, and explicitly approved by the user.

## Reference Files

Load only the file needed for the current step.

- `references/intent-capture-gates.md`
- `references/work-types.md`
- `references/spec-output.md`

## Workflow

1. Run a small Phase 0 exploration.
2. Classify the work type.
3. Ask intent questions until the Intent gate is ready.
4. Ask scope questions until the Scope gate is ready.
5. Ask vision questions until the Vision gate is ready.
6. Present 2 to 3 options with a recommendation.
7. Draft the spec.
8. Run self-review.
9. Present the saved spec for user approval.

## Phase 0

Do this before asking the first question.

- Use CodeMunch instead of broad repo browsing.
- Keep it to a focused baseline pass.
- Read only enough to understand the surface, dependencies, and likely work
  type.
- Always read `.claude/CLAUDE.md` if the work looks cross-cutting.
- Use the type-specific exploration picks from `references/work-types.md`
  after the initial classification.

## Classification Mini-Gate

After baseline exploration:

1. Propose the work type with a short rationale.
2. Wait for one of:
   - `confirmed`
   - `actually: <type>`
   - `unclear: <reason>`
3. Only then continue into deeper questioning.

## Gate Pattern

Intent, Scope, and Vision all use the same loop.

1. Ask one grounded question at a time until the checklist is satisfied.
2. Fire the gate with:

```markdown
## <Gate> Gate

**Confirmed:**
- ...

**Still unclear:**
- ...

**Reply:** `confirmed` / `fix: <what>` / `reopen: <bullet>`
```

3. After confirmation, run the adversarial pass:

```markdown
## Adversarial Check — <Gate>

1. ...
2. ...

**Reply:** `no concerns` / `fix: <what>` / `reopen: <bullet>`
```

4. Only advance when the adversarial pass is clean.

## Snap-Back Rule

If a later answer reopens an earlier gate:

1. announce the snap-back in its own message
2. restate what changed
3. reopen the affected gate
4. rerun its adversarial pass
5. then resume forward progression

Snap-backs are never silent.

## Options Phase

After Intent, Scope, and Vision are locked:

- present 2 to 3 options
- lead with a recommendation
- keep each option to a short name, one-line rationale, pros, and cons

Use the option families from `references/work-types.md`.

## Draft And Review

1. Write the spec with `references/spec-output.md`.
2. Copy locked gate content directly into Intent, Scope, and Vision.
3. Run the self-review checks from `references/spec-output.md`.
4. Present self-review findings with these verbs:
   - `approve: ...`
   - `reject: ...`
   - `skip: ...`
   - `edit <n>: <text>`
   - `add: <finding>`
5. Apply approved edits.
6. Present the saved path for fresh-eye review.
7. User review verbs are:
   - `approved`
   - `fix: <what>`
   - `reopen: <gate>`

## Terminal State

On approval, end with exactly:

> Spec approved and saved at `.claude/specs/YYYY-MM-DD-<slug>-spec.md`. Next step: run `/tailor` to map the codebase against this spec before `/writing-plans`.

## Iron Laws

1. One question per message.
2. Multiple choice preferred.
3. No fishing questions.
4. Gates fire from checklist completeness, not question count.
5. Snap-backs are always announced.
6. This skill writes the spec but never commits it.
