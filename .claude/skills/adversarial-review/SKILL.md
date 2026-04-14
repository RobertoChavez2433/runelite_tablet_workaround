---
name: adversarial-review
description: "Adversarial review of specs or plans. Read-only — finds gaps, security issues, and constraint violations without modifying the input."
user-invocable: true
disable-model-invocation: true
---

# Adversarial Review

Review a spec or plan for gaps, security implications, and constraint
violations. Read-only — never modifies the input document.

## Input

A file path to a spec or plan.

## Output

Review report at `adversarial_reviews/YYYY-MM-DD-<topic>/review.md`

## Workflow

### Step 1: Accept and Load Context
- Read the document
- Identify affected packages
- Load relevant constraints, rules, and source files

### Step 2: Parallel Agent Dispatch

Launch two Opus agents in parallel, both read-only:

**Agent A: Architecture Review** checks:
1. Completeness — all affected files identified?
2. Architecture — package boundaries respected?
3. Constraint violations — conflicts with hard rules?
4. Error handling — all paths covered?
5. State management — reactive types, sealed classes correct?
6. Missing steps — implicit assumptions?

**Agent B: Security Review** checks:
1. Credential handling — storage, transport, lifecycle
2. Shell injection — unescaped values in commands?
3. IPC security — intent and PendingIntent safety
4. Network security — HTTPS, header logging
5. Token lifecycle — clear on logout, race conditions
6. Logging — credential values leaked?

### Step 3: Merge and Categorize

Deduplicate findings. Categorize:

| Category | Meaning |
|----------|---------|
| **MUST-FIX** | Blocks implementation |
| **SHOULD-CONSIDER** | Advisory, can defer with rationale |

### Step 4: Save Report

Save to `adversarial_reviews/YYYY-MM-DD-<topic>/review.md`:

```markdown
# Adversarial Review: <Topic>

**Input**: <path>
**Date**: YYYY-MM-DD

## MUST-FIX
### 1. <Title>
- **Category**: <category>
- **Issue**: <description>
- **Recommendation**: <fix>

## SHOULD-CONSIDER
### 1. <Title>
- **Issue**: <description>
- **Recommendation**: <suggestion>

## Summary
- MUST-FIX: N | SHOULD-CONSIDER: N
- Verdict: PROCEED (0 MUST-FIX) | REVISE (1-3) | BLOCK (4+)
```

### Step 5: Present to User

Present findings with options: Address, Defer, or Dismiss.

## Rules

- Never modify the input document
- Both agents are read-only Opus
- Always save the review report
- Optional in pipeline — can be skipped
