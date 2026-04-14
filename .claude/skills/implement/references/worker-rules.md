# Worker Rules

Static context for implementer and fixer agents.

## Agent Behavior Rules
- NEVER write TODO stubs — every implementation must be real
- NEVER write dead code — no unused imports, variables, classes
- NEVER add Co-Authored-By lines
- Read each target file before editing to preserve existing content
- Implement EXACTLY what the plan specifies — no additions, no omissions

## Build Verification
After completing all implementation substeps, run:
```bash
cd runelite-tablet && ./gradlew build
```
Fix any build errors before reporting completion.

## Project Architecture (curated)
- Manual DI — constructor injection, wired in Application/Activity
- Single-screen navigation — state-driven content switching
- ViewModel via `ViewModelProvider.Factory` + `by viewModels{}` delegate
- SetupActions callback with bind/unbind in onResume/onPause
- AtomicInteger for Termux execution IDs
- Coroutines: structured concurrency, explicit dispatchers (IO/Main)
- Compose: state hoisting, collectAsState(), no side effects in composition
- Shell: `set -euo pipefail`, idempotent, proot compatible

## Domain Context Loading
Before starting work, read the applicable rule files:

| File pattern | Read before working |
|-------------|-------------------|
| **/termux/**/*.kt | .claude/rules/termux-integration.md |
| **/auth/**/*.kt | .claude/rules/auth.md |
| **/installer/**/*.kt | .claude/rules/installer.md |
| **/setup/**/*.kt, **/ui/**/*.kt | .claude/rules/compose-ui.md |
| **/*.kt (coroutines) | .claude/rules/coroutine-safety.md |
| assets/scripts/**/*.sh | .claude/rules/shell-scripts.md |

This is mandatory. Print after loading:
```
[CONTEXT] Domain rules loaded: <filenames>
```

## Progress Reporting
Print a status line after each sub-step:
```
[PROGRESS] Phase N Step X.Y: DONE — <brief description>
```
