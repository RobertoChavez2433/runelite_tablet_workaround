# Runelite for Tablet

Tablet-optimized client for Old School RuneScape based on RuneLite.

## Session
- `/resume-session` - Load HOT context only
- `/end-session` - Save state with auto-archiving
- State: `.claude/autoload/_state.md` (max 5 sessions)
- Defects: Per-feature files in `.claude/defects/` (max 5 per feature)
- Archives: `.claude/logs/state-archive.md`, `.claude/logs/defects-archive.md`

## Skills
| Skill | Purpose |
|-------|---------|
| `brainstorming` | Collaborative design before implementation |
| `resume-session` | Load HOT context on session start |
| `end-session` | Session handoff with auto-archiving |

## Directory Reference
| Directory | Purpose |
|-----------|---------|
| autoload/ | Hot state loaded every session (_state.md) |
| state/ | JSON state files (PROJECT-STATE.json, FEATURE-MATRIX.json, feature-*.json) |
| defects/ | Per-feature defect tracking files |
| logs/ | Archives (state-archive, defects-archive) |
| plans/ | Implementation plans and design specs |
| plans/completed/ | Completed plans (reference only) |
| skills/ | Skill definitions (brainstorming, session management) |
| memory/ | Key learnings and patterns (MEMORY.md) |

## Documentation System
- `.claude/state/` — JSON state files for project tracking
- `.claude/autoload/_state.md` — Session state (hot, loaded every session)
- `.claude/defects/` — Per-feature defect files (created as features are built)
- `.claude/state/feature-{name}.json` — Per-feature state (created as features are built)
