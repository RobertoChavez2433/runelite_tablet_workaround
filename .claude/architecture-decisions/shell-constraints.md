# Shell Constraints

Per-package hard rules and soft guidelines derived from Solved Problems #5, #6, #7 and shell defect patterns.

## Hard Rules (Reject Proposal If Violated)

1. **Proot exit codes are unreliable** — `/proc/self/fd` warnings cause non-zero exit on success. proot-distro self-deletes rootfs on non-zero exit. Verify success with marker files or `which`, never exitCode.

2. **Proot `--kill-on-exit` kills child JVMs** — JvmLauncher uses ProcessBuilder which spawns a child process. When proot's parent exits, `--kill-on-exit` kills the child. Bypass by using `exec java -cp` so the Java process replaces the shell process instead of spawning.

3. **Windows git CRLF breaks shebangs** — `.gitattributes` with `eol=lf` is required for all `.sh` files. Additionally, Kotlin code that reads scripts must defensively `replace("\r","")`.

4. **All `"` inside `bash -c "..."` must be escaped** — Unescaped `"` terminates the outer string. The shell parses remaining text as commands — `(` becomes a subshell start causing syntax errors. Every `"` inside `bash -c "..."` must be `\"`.

5. **Shell credential values must cover ALL metacharacters** — Single-quote escaping misses `$()`, backtick, and other expansions when file is `source`d. Newlines break out of quoted strings. Use double-quote escaping for `\`, `"`, `$`, `` ` ``, `!` PLUS strip `\n`, `\r`, `\0`. Or use `printf %q` (bash-native).

## Soft Guidelines (Discuss Before Proceeding)

- Use `grep -Eo` (POSIX) not `grep -oP` (PCRE) for portability
- Manual rootfs extraction preferred over `proot-distro install` for reliability
- Write post-extraction config: resolv.conf (DNS), hosts, environment (PATH/locale)
- Set `DEBIAN_FRONTEND=noninteractive` for apt-get in no-PTY mode
- `ls | head` under `set -o pipefail` needs `|| true` when no files match
- `apt-get update` returns non-zero without `gpgv` — use `|| true`
- Audit with `awk | grep -n '"' | grep -v '\\"'` for unescaped quotes

## Defect Patterns

- 16 unescaped `"` found in launch-runelite.sh bash -c blocks (Session 39)
- Double quotes inside bash -c "..." blocks terminates outer string

## Related Files

- `rules/shell-scripts.md` — path-triggered rule file
- `defects/_defects-shell.md` — active defect patterns
