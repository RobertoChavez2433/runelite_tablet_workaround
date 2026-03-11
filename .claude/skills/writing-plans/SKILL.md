---
name: writing-plans
description: "Convert an approved spec into a phased implementation plan with dependency analysis and agent routing."
user-invocable: true
---

# Writing Plans

Convert an approved spec from `specs/` into a phased implementation plan.

<HARD-GATE>
Cannot write plan steps until the spec is read AND blast radius is analyzed. No shortcuts.
</HARD-GATE>

## Input

An approved spec from `specs/YYYY-MM-DD-<topic>-spec.md`.

## Output

- Phased plan at `plans/YYYY-MM-DD-<feature-name>.md`
- Dependency analysis at `dependency_graphs/YYYY-MM-DD-<name>/`

## Workflow

### Step 1: Read Approved Spec
- Read the spec file from `specs/`
- Extract: affected packages, constraint references, success criteria
- Verify spec has been approved (check for approval status)

### Step 2: Index Codebase
- **Primary**: Use CodeMunch MCP if available
- **Fallback**: Use Glob + Grep to index relevant files
- Map all files in affected packages
- Identify existing patterns and conventions

### Step 3: Build Dependency Graph
- For each change in the spec, identify:
  - **Direct**: Files that need modification
  - **Dependent**: Files that import/reference modified files
  - **Test**: Test files for modified code
  - **Cleanup**: Documentation, config, or state files to update
- Save analysis to `dependency_graphs/YYYY-MM-DD-<name>/`

### Step 4: Determine Blast Radius
- Categorize changes by risk:
  - **High**: Cross-package changes, API modifications, state management
  - **Medium**: Single-package changes with dependents
  - **Low**: Isolated changes, documentation updates
- Flag constraint violations (check `architecture-decisions/` files)

### Step 5: Write Plan
- Structure as Phase > Step hierarchy
- Each phase is independently verifiable
- Each step specifies:
  - Files to modify/create
  - What to change
  - Agent responsible (see routing table)
  - Dependencies on prior steps

### Step 6: Agent Routing Table
Include this routing table in every plan:

| File Pattern | Agent |
|-------------|-------|
| `**/termux/**/*.kt`, `assets/scripts/**/*.sh` | `termux-shell-agent` |
| `**/auth/**/*.kt` | `auth-agent` |
| `**/installer/**/*.kt` | Main session |
| `**/setup/**/*.kt` | Main session |
| `**/ui/**/*.kt` | Main session |
| Review (any) | `code-review-agent`, `security-review-agent` |
| Performance (any) | `performance-agent` |

### Step 7: Present to User
- Show plan summary with phase count, file count, blast radius
- Present each phase with steps
- Ask for approval before saving

### Step 8: Save
- Save plan to `plans/YYYY-MM-DD-<feature-name>.md`
- Save dependency graph to `dependency_graphs/YYYY-MM-DD-<name>/`

## Plan Format

```markdown
# Plan: <Feature Name>

**Date**: YYYY-MM-DD
**Spec**: specs/YYYY-MM-DD-<topic>-spec.md
**Status**: DRAFT / APPROVED

## Blast Radius
- **Direct**: N files
- **Dependent**: N files
- **Test**: N files
- **Cleanup**: N files

## Agent Routing
[table from Step 6]

## Phase 1: <Name>
### Step 1.1: <Description>
- **Files**: [list]
- **Agent**: [name]
- **Changes**: [description]
- **Depends on**: [prior steps or "none"]

### Step 1.2: ...

## Phase 2: <Name>
...
```

## Rules

- **HARD GATE**: Must read spec before writing any plan steps
- **HARD GATE**: Must analyze blast radius before writing plan
- **No implementation** — this skill produces plans only, never code
- Plans are saved to `plans/`, not `specs/`
- Dependency graphs saved to `dependency_graphs/`

## Checklist

1. **Read spec** — load from specs/
2. **Index codebase** — CodeMunch or Glob+Grep
3. **Build dependency graph** — direct, dependent, test, cleanup
4. **Determine blast radius** — high/medium/low risk classification
5. **Write plan** — Phase > Step hierarchy with agent routing
6. **Present to user** — get approval
7. **Save** — plan to plans/, graph to dependency_graphs/
