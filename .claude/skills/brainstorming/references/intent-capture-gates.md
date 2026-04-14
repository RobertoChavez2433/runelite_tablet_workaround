# Intent Capture Gates

How Intent / Scope / Vision gates work: checklists, trigger conditions,
presentation format, adversarial self-check, and the snap-back rule.

## The Three Gates

Every brainstorm clears three gates in order: **Intent -> Scope -> Vision**.

| Gate | Definition | Captures |
|------|------------|----------|
| **Intent** | Why this exists at all | Problem, who feels it, measurable success criteria, why now |
| **Scope** | What is and isn't in this effort | In scope (v1), deferred, out of scope, constraints, non-goals |
| **Vision** | How it should feel to use when done | User journey, key interactions, acceptance-by-feel |

## Baseline Checklist Items

### Intent baseline

- [ ] **Problem statement** — what's broken or missing? One sentence, concrete.
- [ ] **Felt by whom** — the end user? A developer? A downstream system?
- [ ] **Current pain cost** — how bad is it today? How often?
- [ ] **Success criterion #1** — one measurable outcome.
- [ ] **Success criterion #2** — at least one more.
- [ ] **Why now** — external deadline, dependency, or blocking bug?
- [ ] **Non-solution framing check** — has the user described a solution instead of a problem?

### Scope baseline

- [ ] **In scope for v1** — concrete list
- [ ] **Deferred** — things we'll do later but not now
- [ ] **Out of scope forever** — explicit rejections
- [ ] **Hard constraints** — security invariants, platform limits, proot restrictions
- [ ] **Non-goals** — behaviors this effort must *not* introduce
- [ ] **Interference check** — collides with other in-flight work?
- [ ] **Security boundary check** — touches auth / credentials / IPC / shell injection?

### Vision baseline

- [ ] **Primary user journey** — step-by-step from trigger to outcome
- [ ] **Key interactions** — the 2-5 moments that define whether it feels right
- [ ] **Acceptance-by-feel** — how the user recognizes "yes, this is what I wanted"
- [ ] **Failure modes the user should see** — offline, permission denied, timeout, etc.

## Gate Trigger Rule

A gate fires **when and only when** the internal checklist has zero unsatisfied
items. No minimum question count, no maximum.

## Gate Presentation Format

```
## <Gate name> Gate

**Confirmed:**
- <bullet>

**Still unclear:**
- <checklist item>

**Reply:**
- `confirmed` — advance to the adversarial check
- `fix: <what>` — correct a specific bullet
- `reopen: <bullet>` — reopen with more detail
```

## Adversarial Self-Check

After each gate passes, run four hunts:

1. **Misinterpretation** — paraphrased away a nuance?
2. **Contradiction** — bullets contradict each other or CLAUDE.md constraints?
3. **Unknown unknowns** — what would a hostile reviewer ask?
4. **Scope creep** — did scope quietly widen?

```
## Adversarial Check — <Gate name>

1. <hunt type>: <concern> -> <suggested clarification>

**Reply:** `no concerns` / `<number>: <clarification>` / `ignore <number>: <reason>`
```

## Snap-Back Rule

If a later gate surfaces a contradiction with an earlier locked gate:

1. announce the snap-back in its own message
2. restate what shifted
3. re-present the earlier gate
4. rerun its adversarial check
5. resume forward only after re-confirmation

Snap-backs are never silent.

## Question Style Rules

1. One question per message.
2. Prefer multiple choice with a `D: other` escape.
3. Ground questions in Phase 0 findings.
4. Never fish for permission — just check silently.
5. Never ask the same thing twice.
6. Never propose solutions inside a question.
7. Never dump a questionnaire.
