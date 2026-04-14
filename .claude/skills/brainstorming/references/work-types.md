# Work Types

Per-type tailoring for the brainstorming skill. Each type has classification
signals, per-gate checklist additions, CodeMunch exploration picks, and options.

## Classification Mini-Gate

Valid types: `new feature`, `feature add/mod`, `bug fix`, `refactor+`,
`security hardening`, `documentation`.

## Primary Work Types

### 1. New Feature

**Signals:** capability the app does not have today.

**Phase 0 picks:** `get_repo_outline`, `get_file_tree`, `search_symbols`,
`get_coupling_metrics` on closest existing feature.

**Intent additions:**
- Adjacent feature check
- Does this introduce new Termux commands or shell scripts?

**Scope additions:**
- New shell scripts vs reuse
- Offline/proot compatibility expectation

**Vision additions:**
- Entry point — setup screen, new activity, or existing flow?
- Empty state

**Options:** A. Minimal v1 | B. Full v1 | C. Phased

### 2. Feature Add / Modification

**Signals:** user names an existing module or screen.

**Phase 0 picks:** `search_symbols`, `get_file_outline`, `get_call_hierarchy`,
`get_coupling_metrics`, `get_related_symbols`.

**Intent additions:**
- Existing behavior baseline
- Delta vs rewrite
- Who relies on current behavior

**Scope additions:**
- Surface area — UI only, Kotlin only, shell only, or cross-layer?
- Backwards compatibility

**Vision additions:**
- Before/after moment
- Discoverability of the change

**Options:** A. Surgical | B. Variant | C. Replace-and-deprecate

### 3. Bug Fix

**Signals:** something broken, error, crash, wrong output.

**Phase 0 picks:** `search_symbols`, `get_symbol_source`, `get_call_hierarchy`,
`get_changed_symbols`, `get_churn_rate`.

**Intent additions:**
- Reproduction steps — deterministic or intermittent?
- First observed — which commit or session?
- Correct behavior in concrete terms

**Scope additions:**
- Symptom vs root cause
- Regression test level

**Vision additions:**
- How the user recognizes the fix

**Options:** A. Symptom fix | B. Root-cause fix | C. Restructure

### 4. Refactor+

**Signals:** structural problem — too big, tangled, blocking new work.

**Phase 0 picks:** `get_file_outline`, `get_symbol_complexity`,
`get_coupling_metrics`, `get_dependency_cycles`, `get_extraction_candidates`.

**Intent additions:**
- Pain point — what specifically hurts?
- Behavior preservation promise

**Scope additions:**
- Blast radius limit
- Rollback strategy

**Vision additions:**
- What the new shape looks like in one sentence

**Options:** A. Minimum | B. Single class/file | C. Whole subsystem

## Secondary Work Types (Generic Gate)

### Security Hardening

**Signals:** auth, credentials, IPC, shell injection, storage.

**Additions:**
- Intent: threat model
- Scope: in-scope surface, always include security-review-agent pass
- Vision: how defense becomes visible

**Options:** A. Tight patch | B. Layered defense | C. Policy change

### Documentation

**Signals:** docs, CLAUDE.md, rules, onboarding.

**Additions:**
- Intent: who reads this and what do they need
- Scope: which files, whether auto-loaded files are touched
- Vision: how the reader finds the right doc

**Options:** A. Tight edit | B. Restructure | C. New doc
