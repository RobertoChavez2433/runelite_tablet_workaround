# Reviewer Rules

Static context for all reviewer types.

## Reviewer Baseline Rules
- You are READ-ONLY. NEVER modify source code.
- Scope: review ONLY files from the current phase, not the entire codebase.
- Always read the spec first — it is the source of truth for intent.
- Always read the plan — it defines what should have been implemented.

## Severity Calibration

| Level | Definition | Blocks approval? |
|-------|-----------|-----------------|
| CRITICAL | Breaks functionality, security vuln, spec requirement completely missing | YES |
| HIGH | Wrong behavior, missing error handling, key requirement partially missing | YES |
| MEDIUM | Suboptimal pattern, missing edge case, doesn't fully match spec intent | YES |
| LOW | Style, naming, minor improvement, nitpick | NO (logged only) |

## Verdict Rules
- **"approve"** — Zero findings at CRITICAL, HIGH, or MEDIUM severity
- **"reject"** — One or more findings at CRITICAL, HIGH, or MEDIUM severity
- LOW findings do not affect verdict but MUST still be reported

## Implementation Shortcuts (CRITICAL severity — always flag)

Grep for ALL of these patterns across every file in the phase:

- `// TODO`, `// FIXME`, `// HACK`, `// PLACEHOLDER` — deferred work
- `throw NotImplementedError()` or `TODO()` — stub implementations
- Empty method bodies or methods that only return null/emptyList()/false
- Catch blocks that silently swallow errors with no logging
- Hardcoded return values that bypass actual logic
- Commented-out code blocks
- `as Any` casts to bypass type safety

## Anti-Patterns to Flag
- God Class (>500 lines, too many responsibilities)
- Copy-Paste (duplicate logic across files)
- Magic Values (hardcoded numbers/strings without constants)
- Over-Engineering (abstractions for single use cases)
- Missing Null Safety (force unwraps `!!`, missing null checks)
- Async Anti-patterns (missing timeout on CompletableDeferred, CancellationException swallowed)
- Shell scripts missing `set -euo pipefail`

## Domain Context Loading
Before reviewing, read the applicable rule files:

| File pattern | Read before reviewing |
|-------------|---------------------|
| **/termux/**/*.kt | .claude/rules/termux-integration.md |
| **/auth/**/*.kt | .claude/rules/auth.md |
| **/installer/**/*.kt | .claude/rules/installer.md |
| **/setup/**/*.kt, **/ui/**/*.kt | .claude/rules/compose-ui.md |
| **/*.kt (coroutines) | .claude/rules/coroutine-safety.md |
| assets/scripts/**/*.sh | .claude/rules/shell-scripts.md |

This is mandatory. Read the matching rule files before writing any findings.

## Output
Return plain markdown findings in the caller's requested format.
Do NOT write files.
