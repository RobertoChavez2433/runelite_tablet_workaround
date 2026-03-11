---
name: audit-config
description: "Read-only health check for the .claude/ directory. Validates references, structure, and coverage. Never modifies files."
user-invocable: true
---

# Audit Config

Read-only validation of the `.claude/` directory structure, references, and coverage.

<IRON-LAW>
NEVER modify any files. This skill is read-only and report-only.
</IRON-LAW>

## Output

Report at `outputs/audit-report-YYYY-MM-DD.md`

## 7 Validation Steps

### Step 1: Index Codebase
- **Primary**: Use CodeMunch MCP if available
- **Fallback**: Use Glob + Grep to index all source files
- Build map of packages, files, and classes

### Step 2: Scan .claude/ References
- Read all files in `.claude/` recursively
- Extract path references (file paths, package references, class names)
- Build reference graph

### Step 3: Validate References Exist
- For each path reference found in Step 2, verify it exists on disk
- Flag broken paths (file moved, renamed, or deleted)
- Flag stale references (reference to removed feature or file)

### Step 4: Structural Invariants
Check these invariants:

| Invariant | What to Check |
|-----------|---------------|
| Agent memory dirs | Every agent in `agents/` has matching `agent-memory/{agent}/MEMORY.md` |
| Rule frontmatter | Every file in `rules/` has YAML frontmatter with `paths:` key |
| Constraint files | Every constraint file has Hard Rules and Soft Guidelines sections |
| Doc pairs | Every `feature-{name}-overview.md` has matching `feature-{name}-architecture.md` |
| Defect files | Every `_defects-{package}.md` follows naming convention and has max 5 entries |
| Skill files | Every skill directory has `SKILL.md` with YAML frontmatter |

### Step 5: Security Invariants
Check these security properties:

| Invariant | What to Check |
|-----------|---------------|
| Review agent tools | `code-review-agent`, `security-review-agent` have `Disallowed: Write, Edit, Bash` |
| Performance agent tools | `performance-agent` has `Disallowed: Write, Edit` |
| CLAUDE.md sentinels | CLAUDE.md contains key safety rules (no GlobalScope, CancellationException, etc.) |

### Step 6: Produce Report
Generate report with these sections:

```markdown
# Audit Report: YYYY-MM-DD

## Summary
- Files scanned: N
- References checked: N
- Broken paths: N
- Structural violations: N
- Security violations: N

## Broken Paths
| File | Reference | Status |
|------|-----------|--------|
| [file] | [reference] | Missing / Moved / Stale |

## Orphaned Files
Files in .claude/ not referenced by any other file:
- [file]

## Missing Coverage
Source packages without documentation, rules, or constraints:
- [package]

## Structural Violations
| Invariant | File | Issue |
|-----------|------|-------|
| [invariant] | [file] | [description] |

## Security Violations
| Invariant | File | Issue |
|-----------|------|-------|
| [invariant] | [file] | [description] |

## Stale References
References to files that existed at time of writing but have since changed:
- [reference]
```

### Step 7: Save and Present
- Save report to `outputs/audit-report-YYYY-MM-DD.md`
- Present summary to user
- Offer options:
  1. **Fix auto** — auto-fixable issues (broken paths with obvious replacements)
  2. **Fix manual** — list manual fixes needed
  3. **Defer** — acknowledge and defer to next session

## Rules

- **NEVER modify any files** — read-only, report-only
- Save reports to `outputs/` only
- Check all directories: rules, architecture-decisions, defects, docs/features, agent-memory, agents, skills
- Flag but don't block on orphaned files (they may be intentional)
