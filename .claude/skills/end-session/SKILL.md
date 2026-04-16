---
name: end-session
description: End session with auto-archiving
user-invocable: true
disable-model-invocation: true
---

# End Session

Complete session with proper handoff and auto-archiving.

**CRITICAL**: NO git commands anywhere in this skill.

## Actions

### 1. Gather Summary (From Conversation Context)
Review the current conversation and collect:
- Main focus of session
- Completed tasks
- Decisions made
- Next priorities
- Defects discovered (mistakes, anti-patterns, bugs found) — file these as GitHub issues via `tools/create-issue.ps1` per `.claude/specs/2026-04-16-issue-convention-spec.md`; do NOT write `.claude/defects/*`.

Do NOT run git commands. Use only what you observed during the session.

### 2. Update _state.md
**File**: `.claude/autoload/_state.md`

Write compressed session summary (max 5 lines):
```markdown
### Session N (YYYY-MM-DD)
**Work**: Brief 1-line summary
**Decisions**: Key decisions made
**Next**: Top 1-3 priorities
```

If >5 sessions exist, run rotation:
1. Take oldest session
2. Append to `.claude/logs/state-archive.md` under appropriate month header
3. Remove from _state.md

### 3. File Defects as GitHub Issues

For each defect discovered this session, file a GitHub issue via `tools/create-issue.ps1`. Follow `.claude/specs/2026-04-16-issue-convention-spec.md` for title grammar (`type(scope): subject`), body sections (Problem/Decision/Tradeoff/Evidence), and trailers (`Reason:` required). Do NOT write `.claude/defects/*`.

### 4. Update JSON State Files

**PROJECT-STATE.json** — update session notes, blockers, timestamps.
**feature-{name}.json** — only for features touched this session.

### 5. Display Summary
Present:
- Session summary
- Features touched
- Defects logged (if any)
- Next priorities
- Reminder: Run `/resume-session` to start next session

## Rules
- **NO git commands** — not `git status`, not `git diff`, not any git operation
- All analysis from conversation context only
- Zero user input required
- Defects go to GitHub issues, not local files
