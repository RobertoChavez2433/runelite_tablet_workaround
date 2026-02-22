---
name: resume-session
description: Fast session resume - load state, display summary, ready to work
user-invocable: true
disable-model-invocation: true
---

# Resume Session

Fast context load. No questions — just read state and display summary.

**CRITICAL**: NO git commands anywhere in this skill.

## Actions

### Step 1: Read HOT Context (3 files only)
1. `.claude/memory/MEMORY.md` — Key learnings and patterns
2. `.claude/autoload/_state.md` — Current state
3. `.claude/autoload/_defects.md` — Active defects (auto-loaded)

**DO NOT READ** (lazy load only when needed):
- `.claude/logs/state-archive.md`
- `.claude/logs/defects-archive.md`
- Any rules, docs, constraints, or state JSON files

### Step 2: Display Summary

Print this compact format:

```
**Phase**: [From _state.md]
**Status**: [From _state.md]
**Last Session**: [1-line summary from most recent session entry]
**Active Defects**: [count] ([categories])

**Next Tasks**:
1. [From _state.md "What Needs to Happen Next"]
2. [...]
3. [...]

Ready — what are we working on?
```

That's it. No questions. No context loading. The user's first message IS the intent.

### Step 3: Return Control

Wait for the user to say what they want. Their message determines what happens next:
- If they name a feature -> load feature-specific context on demand
- If they ask about status -> read `state/PROJECT-STATE.json` or `state/FEATURE-MATRIX.json`
- If they want to debug -> load defects and constraints as needed

**Do NOT pre-load any feature context, rules, constraints, or docs.**

## Agent & Skill Reference

| Domain | Use |
|--------|-----|
| New feature / behavior change | `/brainstorming` then `/implement` |
| Bug / unexpected behavior | `/systematic-debugging` |
| Code quality concern | `code-review-agent` (Opus) |
| Performance concern | `performance-agent` (Opus) |

## Context Loading Reference (for when work begins, not this skill)

When work begins on a feature, load context on demand:
- **State**: `state/feature-{name}.json`, `state/PROJECT-STATE.json`
- **Defects**: `.claude/autoload/_defects.md` (already auto-loaded)
- **Plans**: `plans/` for active plans
- **Architecture**: `docs/architecture.md`

## Rules
- **NO git commands** — not `git status`, not `git log`, not any git operation
- **NO questions** — display summary and wait
- **NO pre-loading** — load context only when the user gives direction
