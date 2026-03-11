# Termux Constraints

Per-package hard rules and soft guidelines derived from Solved Problems #1, #4, #5 and Termux gotchas.

## Hard Rules (Reject Proposal If Violated)

1. **Bundle extraction for results** — Termux wraps ALL result data in `getBundleExtra("result")`, NOT flat intent extras. Any code that reads Termux results from flat extras will silently get null.

2. **FLAG_MUTABLE on PendingIntent** — Termux calls `pendingIntent.send(ctx, RESULT_OK, fillInIntent)`. Fill-in extras are silently dropped with `FLAG_IMMUTABLE`. This is non-negotiable.

3. **Cross-UID file access impossible** — App's `filesDir` has different UID than Termux. Files written by the app are unreadable by Termux. Deploy everything via `TermuxCommandRunner` stdin or Termux-accessible paths.

4. **Proot exit codes are unreliable** — `/proc/self/fd` warnings cause non-zero exit even on success. proot-distro interprets non-zero as failure and self-deletes the rootfs. Never trust `exitCode` from proot commands. Verify success with marker files or `which`.

5. **`allow-external-apps` property required** — Must be set in `~/.termux/termux.properties` before any RUN_COMMAND intent will work. Without it, intents are silently ignored.

6. **RUN_COMMAND permission** — `com.termux.permission.RUN_COMMAND` must be declared in the Android manifest.

## Soft Guidelines (Discuss Before Proceeding)

- Use `AtomicInteger` for execution IDs, not `nanoTime()` or random values
- Validate execution_id against the pending results map before processing
- Cancel PendingIntent after result received
- TermuxResultService should be `exported="false"`
- Always set reasonable timeouts on CompletableDeferred.await()

## Defect Patterns

- proot-distro `|| true` does NOT prevent rootfs self-deletion on non-zero exit — use manual rootfs extraction
- `< /dev/null` fixes fd/0 but fd/1 and fd/2 still warn in background mode — non-zero exit still occurs
- proot operations are slow (ptrace overhead): 8-10min for rootfs extraction + Java install is normal

## Related Files

- `rules/termux-integration.md` — path-triggered rule file
- `defects/_defects-termux.md` — active defect patterns
