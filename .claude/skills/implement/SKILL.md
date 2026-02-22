---
name: implement
description: "Orchestrate implementation by dispatching agents for coding, review, and verification. Never writes code itself."
user-invocable: true
---

# /implement — Implementation Orchestrator

Dispatches agents for implementation. **Never writes code itself.**

<IRON-LAW>
NEVER use Edit or Write tools — only Read, Glob, Grep, Bash (read-only), Task.
</IRON-LAW>

## Four-Step Workflow

### Step 0: Load the Plan

1. Read the plan file (user provides path or name)
2. Identify all implementation phases
3. Glob/Grep to find source files each phase touches
4. Present phase list to user before starting

### Step 1: Implementation Phases

- Phases with dependencies: **sequential**
- Independent phases: **parallel** (max 3 concurrent)
- Each implementer receives:
  - Full plan text
  - Phase assignment
  - Current source files (read contents)
  - Conventions from `.claude/CLAUDE.md`
  - Active defects from `.claude/autoload/_defects.md`
- Milestone report after each phase: files modified, build status

**Implementer config**:
| Field | Value |
|-------|-------|
| subagent_type | general-purpose |
| model | sonnet |
| Writes code? | Yes |

### Step 2: Code Quality Review Loop

**Trigger**: All phases complete + build passes (`./gradlew build` from `runelite-tablet/`)

1. Launch code-review-agent (Opus) on all modified files
2. If P0 or P1 issues found:
   - Dispatch Sonnet fixer agent with review findings
   - Rebuild
   - Re-launch code-review-agent
   - Repeat until `QUALITY GATE: PASS`
3. P2 nitpicks: collected for final report, don't block

**Reviewer config**:
| Field | Value |
|-------|-------|
| subagent_type | general-purpose |
| model | opus |
| prompt | Include full text of `.claude/agents/code-review-agent.md` |
| Writes code? | No |

### Step 3: Plan Completion Review Loop

**Trigger**: Code quality gate passed

1. Launch fresh Sonnet general-purpose agent with three tasks:
   - **Checklist verification**: Read plan, read all modified files, confirm every requirement is met
   - **Build verification**: Run `./gradlew build` and confirm it passes
   - **Functional spot-check**: Grep for key patterns the plan requires
2. If MISSING or MISMATCH found:
   - Dispatch Sonnet fixer agent
   - Re-verify
   - Repeat until `COMPLETION GATE: PASS`

### Step 4: Final Summary

Present to user:
- Phases completed
- Files modified (list)
- Review cycles count
- P2 nitpicks (for awareness)
- Build status
- `Ready to review and commit.`

**Orchestrator does NOT commit or push.**

## Agent Type Reference

| Role | subagent_type | model | Writes Code? |
|------|--------------|-------|-------------|
| Implementer | general-purpose | sonnet | Yes |
| Code Quality Reviewer | general-purpose | opus | No |
| Completion Reviewer | general-purpose | sonnet | No |
| Fixer | general-purpose | sonnet | Yes |

## Project Context (Always Provide to Agents)

- Source: `runelite-tablet/app/src/main/java/com/runelitetablet/`
- Scripts: `runelite-tablet/app/src/main/assets/scripts/`
- Build: `./gradlew build` from `runelite-tablet/`
- Conventions: `.claude/CLAUDE.md`
- Prior defects: `.claude/autoload/_defects.md`
