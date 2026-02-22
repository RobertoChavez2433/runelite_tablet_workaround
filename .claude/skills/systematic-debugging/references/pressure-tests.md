# Pressure Tests for Debugging

Questions to ask yourself when debugging gets stuck. If you can't answer "yes" confidently, go back to Phase 1.

## Root Cause Verification

- Can you explain the root cause in one sentence without using the word "somehow"?
- Can you predict exactly when the bug will and won't occur?
- Does your explanation account for ALL observed symptoms, not just the main one?
- If you removed your fix, can you explain precisely why the bug would return?

## Fix Quality

- Is your fix at the root cause, or at a symptom?
- Does your fix change behavior only for the broken case, or does it also affect working cases?
- Is there a simpler fix you haven't considered?
- Would this fix survive a code review by someone unfamiliar with the bug?

## Common Traps

### "It's a timing issue"
- Have you actually measured the timing, or are you guessing?
- Can you reproduce it deterministically with controlled timing?
- Adding a sleep is never a fix — it's a Band-Aid.

### "It's a Termux/proot issue"
- Have you tested the same operation outside of Termux/proot?
- Have you checked Termux GitHub issues for known problems?
- Is the issue in how we call Termux, or in Termux itself?

### "The state is wrong"
- At exactly which point does the state diverge from expected?
- Have you logged the state at each transition?
- Is the state mutated somewhere you don't expect?

### "It works on my machine"
- What's different about the environments?
- Android version? Termux version? proot-distro version?
- Permissions granted? Storage available?

## When to Escalate

- After 3 failed hypotheses: the problem is likely not where you think
- After 1 hour on the same bug: document what you know, take a break
- If the fix requires understanding Termux internals: check their source/issues first
- If the fix requires Android framework knowledge: check official docs, not Stack Overflow
