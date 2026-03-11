---
name: adversarial-review
description: "Adversarial review of specs or plans. Read-only — finds gaps, security issues, and constraint violations without modifying the input."
user-invocable: true
---

# Adversarial Review

Review a spec or plan for gaps, security implications, and constraint violations. **Read-only** — never modifies the input document.

## Input

A file path to either:
- A spec from `specs/YYYY-MM-DD-<topic>-spec.md`
- A plan from `plans/YYYY-MM-DD-<feature-name>.md`

## Output

Review report at `adversarial_reviews/YYYY-MM-DD-<topic>/review.md`

## Workflow

### Step 1: Accept Input (Orchestrator)
- Accept file path from user (spec or plan)
- Read the document
- Identify affected packages from the document content

### Step 2: Load Context (Orchestrator)
- Read relevant constraint files: `architecture-decisions/{package}-constraints.md`
- Read relevant defect files: `defects/_defects-{package}.md`
- Read relevant feature docs: `docs/features/feature-{package}-overview.md`
- Read the actual source files referenced in the plan/spec
- Read relevant rule files: `rules/{rule}.md`

### Step 3: Parallel Agent Dispatch (Orchestrator)

Launch **two Task agents in parallel** in a single message using two Task tool calls. Both agents are `general-purpose` type, model `opus`. Both are read-only research agents — tell them NOT to write or edit any files.

Provide each agent with:
- The full text of the plan/spec being reviewed
- The full text of all loaded context (constraints, defects, features, rules)
- The source file contents for all files referenced in the plan

#### Agent A: Architecture Review

Prompt the agent with the architecture review checklist and instruct it to return findings in this format:

```
Review the following plan/spec for architectural issues. You have access to the plan text, constraint files, defect patterns, feature docs, rules, and source files below.

Review checklist:
1. **Completeness** — Are all affected files identified? Are there files that should be modified but aren't listed?
2. **Architecture** — Does the proposal respect package boundaries (auth/setup/ui/termux/installer)? Are cross-package dependencies properly managed?
3. **Constraint violations** — Does anything conflict with hard rules in the constraint files? Check EVERY hard rule in each relevant constraint file.
4. **Error handling** — Are all error paths covered? Are there missing edge cases? Does the proposal handle CancellationException correctly (coroutine safety)?
5. **State management** — Are new state variables reactive (MutableStateFlow not @Volatile)? Are sealed class patterns correct? Are flags cleaned up on all paths?
6. **Existing patterns** — Does the proposal follow existing code patterns? Would the implementation work with the current code structure?
7. **Missing steps** — Are there implicit steps the plan assumes but doesn't document?

For each finding, output:
- **Title**: Short description
- **Severity**: MUST-FIX or SHOULD-CONSIDER
- **Category**: Architecture / Completeness / Constraint Violation / Error Handling / State Management
- **Location**: Which section of the plan or which source file
- **Issue**: What's wrong
- **Recommendation**: How to fix it

[paste plan text, constraint files, defect files, feature docs, rules, and source files here]
```

#### Agent B: Security Review

Prompt the agent with the security review checklist and instruct it to return findings in this format:

```
Review the following plan/spec for security issues. You have access to the plan text, constraint files, defect patterns, security review checklist, and source files below.

Review checklist:
1. **Credential handling** — Are tokens, sessions, credentials properly secured? Are they stored in EncryptedSharedPreferences, not plain SharedPreferences? Are access tokens in-memory only?
2. **Credential transport** — Are tokens passed via temp file (not CLI args)? Is the temp file properly cleaned up? Are credential values shell-escaped?
3. **Shell injection** — Are any user/API-derived values interpolated into shell commands without proper escaping?
4. **IPC security** — Are intents, broadcasts, pending intents secure? Are PendingIntent flags correct (FLAG_MUTABLE where needed)?
5. **Network security** — HTTPS enforced? Auth headers not logged? Proper error body sanitization?
6. **Token lifecycle** — Are tokens cleared on logout/expiry? Is the clear-and-re-auth flow safe from race conditions? Could stale tokens leak?
7. **Logging** — Could any new log statements leak credential values? Are error messages sanitized?
8. **Data leakage** — Could the proposed changes expose credentials via ActivityResult, Intent extras, or process death state?

For each finding, output:
- **Title**: Short description
- **Severity**: MUST-FIX or SHOULD-CONSIDER
- **Category**: Credential Security / Shell Injection / IPC Security / Network Security / Token Lifecycle / Logging / Data Leakage
- **Location**: Which section of the plan or which source file
- **Issue**: What's wrong
- **Risk**: What could happen if not fixed
- **Recommendation**: How to fix it

[paste plan text, constraint files, defect files, security checklist, and source files here]
```

### Step 4: Merge and Categorize (Orchestrator)

Collect findings from both agents. Deduplicate — if both agents flagged the same issue, keep the more detailed version.

Categorize each finding:

| Category | Meaning | Action |
|----------|---------|--------|
| **MUST-FIX** | Blocks implementation — constraint violation, security flaw, or missing critical step | Must be addressed before proceeding |
| **SHOULD-CONSIDER** | Advisory — potential improvement, edge case, or risk to monitor | Can be deferred with documented rationale |

### Step 5: Save Report (Orchestrator)

Save to `adversarial_reviews/YYYY-MM-DD-<topic>/review.md` with format:

```markdown
# Adversarial Review: <Topic>

**Input**: <file path>
**Date**: YYYY-MM-DD
**Affected Packages**: <list>

## MUST-FIX (Blocks Implementation)

### 1. <Finding Title>
- **Category**: Architecture / Security / Constraint Violation
- **Location**: <section or file reference>
- **Issue**: <description>
- **Constraint**: <reference to constraint file if applicable>
- **Recommendation**: <specific fix>

## SHOULD-CONSIDER (Advisory)

### 1. <Finding Title>
- **Category**: <category>
- **Issue**: <description>
- **Risk**: <potential impact>
- **Recommendation**: <suggestion>

## Summary
- MUST-FIX: N findings
- SHOULD-CONSIDER: N findings
- Verdict: PROCEED / REVISE / BLOCK
```

### Step 6: Present to User (Orchestrator)

Present findings with options:
1. **Address** — Fix MUST-FIX items, update the spec/plan
2. **Defer** — Accept risk, document rationale, proceed anyway
3. **Dismiss** — Finding is not applicable, document why

## Properties

- **Read-only** — Never modifies the input spec or plan
- **Orchestrator pattern** — Main window stays clean, agents do the heavy reading
- **Parallel agents** — Architecture and security reviews run simultaneously
- **Optional in pipeline** — Can be skipped; brainstorming can hand off directly to writing-plans
- **Reusable** — Works on both specs and plans
- **No implementation** — Produces review reports only

## Rules

- NEVER modify the input document
- ALWAYS read constraint files and source files before dispatching agents
- ALWAYS dispatch both agents in parallel (single message, two Task tool calls)
- ALWAYS pass the full plan text AND all context to each agent — they cannot read files themselves efficiently without the context
- ALWAYS save the review report to `adversarial_reviews/`
- Categorize as MUST-FIX or SHOULD-CONSIDER (no other categories)
- Verdict is PROCEED (0 MUST-FIX), REVISE (1-3 MUST-FIX), or BLOCK (4+ MUST-FIX)
- Both agents are read-only research — instruct them NOT to write or edit files
- Use `model: "opus"` for both agents (matches existing agent definitions)
