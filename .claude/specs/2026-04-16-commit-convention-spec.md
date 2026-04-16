# Tablite Commit Convention

**Date**: 2026-04-16 | **Status**: Active

## Why

Make history queryable after the fact. Every non-trivial commit should explain the decision, not the mechanics. The diff shows what changed; the commit body says why.

## Subject line

```
<type>(<scope>): <subject — imperative mood, ≤72 chars>
```

**Types** (9): `feat` `fix` `refactor` `perf` `test` `docs` `chore` `ci` `build`

**Scope** — required for `feat` / `fix` / `refactor` / `perf`. Optional on the rest. Scoped lightweight types (`docs(auth)`, `chore(scripts)`) become **narrative** and must follow the body rules.

**Scopes allowlist** — `scripts/git/valid-scopes.txt`. Unknown scope = hard reject.

## Body (narrative commits only)

Required for all substantive types AND scoped lightweight types.

Explain one or more of:

- **Problem:** what forced this change
- **Decision:** what approach was chosen and why
- **Tradeoff:** what was rejected or accepted
- **Evidence:** tests, device proof, logs, repro

Rules: wrap at 72, ≥20 non-whitespace chars, imperative mood.

## Trailers

Stay in the trailer block (blank line before them). Parsed by `git interpret-trailers`.

- **`Reason:`** — one-line forcing function. **Required** on every narrative commit. No exceptions.
- `Decision:` — durable choice, one line
- `Tradeoff:` — accepted/rejected tradeoff, one line
- `Evidence:` — proof, one line
- `Follow-up:` — known remaining work
- `Refs:` — `#<issue>`, `ADR-<n>`, or URL
- `BREAKING CHANGE:` — if applicable

Loose prose is not allowed in the trailer block — only `Key: value` lines.

## Mechanical commits

Unscoped `test` / `docs` / `chore` / `ci` / `build` commits may stay terse — no body, no `Reason:`. Use only when no real decision was made (lockfile bumps, formatter-only).

## Enforcement

`.git/hooks/commit-msg` → `scripts/git/commit-msg` rejects:

- Bad subject grammar
- Invalid type
- Missing scope on `feat` / `fix` / `refactor` / `perf`
- Unknown scope
- Missing body on narrative commits (<20 non-whitespace chars)
- Missing `Reason:` trailer on narrative commits

Passthroughs: merge commits, `Revert "..."` with commit ref, `fixup!` / `squash!`.

## Setup

```bash
cp scripts/git/commit-msg .git/hooks/commit-msg
chmod +x .git/hooks/commit-msg
git config commit.template .gitmessage
```

## Real examples

```
feat(native): implement lorieUploadToScreen for direct AHB writes

exaDoPutImage bailed at `!pExaScr->info->UploadToScreen` and fell
through to ExaCheckPutImage → fbPutImage (software). With a driver-
side UploadToScreen that locks the AHB once and memcpy's row-strided,
RuneLite's 22-row PutImage strips go 99.6% accelerated.

Reason: EXA software fallback was the dominant FPS ceiling, not VirGL
Evidence: ExaTrace counter 1837 accel / 8 fallback over 150s on R52X90378YB
Refs: #1
```

```
fix(setup): share ScriptManager deploy state across instances

HybridX11TestReceiver constructed a fresh ScriptManager per broadcast;
the per-instance scriptsDeployed flag missed the activity setup-flow's
deployment, triggering re-deploy. Termux's RunCommandService then
couldn't call back into TermuxResultService because Android 14 denies
background FGS starts for receiver-triggered services → 30s timeout.

Reason: every launcher broadcast was ANRing because of redundant re-deploy
```

```
chore: bump lockfile
```

## Queries

```bash
# Why did this file change?
git log --follow --format="%h %s%n%b%n---" -- path/to/file

# All reasons touching one scope
git log --grep="Reason:" -- runelite-tablet/app/src/main/cpp/ --format="%h %s%n%(trailers:key=Reason)%n"

# Every decision made last month
git log --since="1 month ago" --grep="^feat\|^refactor" --format="%h %s%n%b%n---"
```
