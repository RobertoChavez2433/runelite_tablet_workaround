# Severity Standard

## Severity Levels

| Level | Definition | Blocks Approval? |
|-------|------------|-----------------|
| CRITICAL | Breaks functionality, security vulnerability, or spec requirement completely missing. | YES |
| HIGH | Significant issue. Wrong behavior, missing error handling, key requirement partially missing. | YES |
| MEDIUM | Quality issue. Suboptimal pattern, missing edge case, doesn't fully match spec intent. | YES |
| LOW | Nitpick. Style, naming, minor improvement. | NO (logged only) |

## Verdict Rules

- **"approve"** — Zero findings at CRITICAL, HIGH, or MEDIUM severity
- **"reject"** — One or more findings at CRITICAL, HIGH, or MEDIUM severity

LOW findings are reported but do NOT affect verdict.

## Fix Scope

- Fixer agents fix CRITICAL, HIGH, and MEDIUM findings only
- LOW findings are skipped by fixers unless a calling workflow says otherwise

## Finding Format (for reviewers)

Each finding MUST include:

| Field | Description |
|-------|-------------|
| `id` | Sequential identifier (F1, F2, ...) |
| `severity` | One of `critical`, `high`, `medium`, `low` |
| `category` | `completeness`, `code-quality`, or `security` |
| `file` | Path to the affected file |
| `line` | Line number (or `null` if not applicable) |
| `finding` | Clear description of the issue |
| `fix_guidance` | Specific, actionable fix instruction |
| `spec_reference` | Which spec requirement this relates to (completeness only) |
