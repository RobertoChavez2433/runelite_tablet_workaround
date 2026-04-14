---
name: systematic-debugging
description: "Interactive root-cause-first debugging for issues that need evidence, instrumentation, and a clear stop-before-fixing gate."
user-invocable: true
disable-model-invocation: true
---

# Systematic Debugging

Debug with the user, not around the user. This skill is for evidence-driven
root cause analysis, especially when async behavior, IPC timing, proot quirks,
or shell script failures matter.

## Iron Law

No fixes before root cause. No code without explicit user approval.

## Mode Choice

Ask once at the start:

```text
Quick mode or Deep mode?
```

- `Quick`: direct investigation with targeted reads and logs
- `Deep`: adds a read-only `debug-research-agent` in parallel

## Workflow

### 1. Triage

- confirm the bug statement and repro steps
- check whether this looks like a known issue pattern
- identify the smallest likely code path
- decide whether logs, manual repro, or targeted reads are needed

Then present the triage summary before moving on.

### 2. Coverage And Instrumentation

- inspect existing logs on the suspected path
- identify blind spots
- add temporary hypothesis markers only where evidence is missing
- suggest permanent logging only when a real long-term coverage gap exists

Never log secrets, tokens, or raw credentials.

### 3. Reproduce

- prefer the simplest path that gives clear evidence
- capture only the evidence needed to isolate the failure point

### 4. Evidence Analysis

- compare expected vs actual behavior
- identify the first missing, wrong, or failing boundary
- compare against a similar working path when useful
- read background research findings only if they sharpen the diagnosis

### 5. Root Cause Report

Present:

- bug summary
- key evidence
- most upstream root cause
- proposed fix approach
- likely files to change
- risk level

Then stop and wait for the user to choose:

- `approved`
- `investigate more`
- `wrong direction`
- `defer`

## If The User Approves A Fix

After approval:

1. implement the approved fix
2. verify the fix addresses the root cause
3. check for regressions
4. remove all temporary hypothesis markers
5. keep only logging that fills a real permanent gap

## Stop Conditions

Stop and reassess if:

- 3 hypotheses fail
- the issue expands beyond 5 files without a clear upstream cause
- the proposed fix only suppresses symptoms
- you still cannot explain why the bug exists

## Project-Specific Debug Checklist

| Symptom | Likely Cause | Check |
|---------|-------------|-------|
| Setup hangs on a step | CompletableDeferred never completed | Result service callback? ID match? |
| APK install fails silently | PackageInstaller session error | InstallResultReceiver status? Signing? |
| Coroutine keeps running | CancellationException swallowed | Generic catch without re-throw? |
| Shell script fails partway | Missing set -e or unquoted var | Error handling, variable expansion |
| X11 display not connecting | Termux:X11 not started | sleep enough? DISPLAY=:0? |
| Compose UI not updating | StateFlow issue | collectAsState()? value assignment? |
| Termux permission denied | RUN_COMMAND not granted | allow-external-apps set? |

## Deep Mode

When using Deep mode, launch `debug-research-agent` in the background at
session start with the bug description and suspected paths. Read its output
once during evidence analysis. Do not re-launch after it completes.

## Output Shape

Keep status updates short. Root-cause reports should be structured, but not
longer than needed to justify the next decision.
