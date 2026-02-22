# Logs Directory

## Active State (Hot Memory)
Located in `../autoload/`:
- `_state.md` - Current 5 sessions (loaded every session via `/resume-session`)

## Defects (Per-Feature)
Located in `../defects/`:
- `_defects-{feature}.md` - Per-feature defect tracking (max 5 per feature)

## Archives (Cold Storage)
- `state-archive.md` - Older sessions, auto-rotated when >5 active in `_state.md`
- `defects-archive.md` - Older/resolved defects, auto-rotated from per-feature files

## Rotation Rules
1. When `_state.md` exceeds 5 sessions:
   - Move oldest entry to `state-archive.md`

2. When a per-feature defect file exceeds 5 entries:
   - Move oldest/resolved entries to `defects-archive.md`
