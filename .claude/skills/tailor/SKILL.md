---
name: tailor
description: "Builds a focused CodeMunch-backed context package for an approved spec. Use after brainstorming and before writing-plans when implementation context needs to be mapped."
user-invocable: true
disable-model-invocation: true
---

# Tailor

Map the codebase against an approved spec and write a durable tailor directory
at `.claude/tailor/YYYY-MM-DD-<spec-slug>/`.

## When To Use

Use this skill when writing-plans would benefit from:

- verified file and symbol context
- dependency and blast-radius mapping
- reusable implementation patterns
- grounded literals and path verification

Skip it only when the change is small enough that the spec already names the
full implementation surface accurately.

## Hard Gates

Do not write tailor output until:

1. the spec has been read
2. the touched surface has been mapped with CodeMunch
3. key paths, symbols, and literals have been verified
4. unresolved gaps have either been filled or explicitly flagged

Never include secrets or credential values in tailor output.

## Workflow

1. Read the approved spec.
2. Derive the spec slug and create `.claude/tailor/YYYY-MM-DD-<spec-slug>/`.
3. Run a focused CodeMunch research pass.
4. Identify reusable implementation patterns.
5. Verify ground truth.
6. Fill any remaining gaps with read-only research agents only if needed.
7. Write the tailor directory.
8. Present a short summary and stop.

## Research Sequence

Use CodeMunch as the primary source of truth.

Minimum expected sequence:

1. resolve or refresh the repo index
2. outline every touched file named by the spec
3. map dependency graphs for the key files
4. map blast radius and importers for the changed symbols
5. search the key symbols named in the spec
6. pull source for the symbols the writer will need to follow

Optional when needed:

- ranked context
- context bundle
- class hierarchy
- dead-code scan

Avoid broad repo browsing unless CodeMunch fails to answer something specific.

## Pattern Discovery

Capture only patterns the implementer is likely to reuse.

For each useful pattern:

- explain how this repo does it in 2 to 3 sentences
- cite 1 to 2 real exemplars
- list reusable methods or helpers with signatures
- note the imports or ownership boundaries that matter

Do not write pattern essays. Tailor output is for execution, not for teaching.

## Ground Truth

Verify all implementation-critical literals against the live codebase:

- file paths
- class and method names and signatures
- intent action strings and bundle keys
- shell script paths and variable names
- XML resource names

If something cannot be verified, flag it plainly instead of guessing.

## Research Agents

Use read-only agents only for gaps CodeMunch cannot close, such as:

- ambiguous dependency chains
- missing symbol context
- cross-feature interactions not obvious from the graph

Constraints:

- read-only tools only
- one concrete question per agent
- max 3 agents
- summarize only the findings needed to finish tailor output

## Output Contract

Write this directory:

```text
.claude/tailor/YYYY-MM-DD-<spec-slug>/
├── manifest.md
├── dependency-graph.md
├── ground-truth.md
├── blast-radius.md
├── patterns/
│   └── <pattern-name>.md
└── source-excerpts/
    ├── by-file.md
    └── by-concern.md
```

### File Purposes

- `manifest.md`: what was analyzed and what was produced
- `dependency-graph.md`: upstream and downstream impact
- `ground-truth.md`: verified literals and flagged gaps
- `blast-radius.md`: affected files, symbols, and cleanup targets
- `patterns/*`: compact reusable implementation patterns
- `source-excerpts/*`: only the source excerpts the writer is likely to need

## Summary Format

End with:

```text
Tailor complete.

Output: .claude/tailor/YYYY-MM-DD-<spec-slug>/
Patterns: <count>
Files analyzed: <count>
Ground truth: <verified> verified, <flagged> flagged
Research gaps: none | <summary>
```

Do not auto-run `/writing-plans`.
