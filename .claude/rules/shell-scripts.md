---
paths:
  - "assets/scripts/**/*.sh"
---

# Shell Script Rules

## Hard Rules

1. **Always `set -euo pipefail`** at the top of every script
2. **Idempotent and retry-safe** — every script must be safe to re-run without side effects
3. **No hardcoded paths** — use variables derived from `$PREFIX`, `$HOME`, or script-relative paths
4. **Proper quoting** — all variable expansions must be double-quoted (`"$var"` not `$var`)
5. **proot compatible** — no FUSE (AppImage/Flatpak/Snap won't work), no systemd (`systemctl`), no mount
6. **All `"` inside `bash -c "..."` blocks must be escaped** — unescaped `"` terminates the outer string; remaining text is parsed as commands (causes syntax errors with `(` becoming subshell start)
7. **Windows git CRLF breaks shebangs** — `.gitattributes` with `eol=lf` required; defensive `replace("\r","")` in Kotlin code that reads scripts

## Proot-Specific Rules

- **proot exit codes lie** — `/proc/self/fd` warnings cause non-zero exit on success; verify with marker files or `which`, never `exitCode`
- **proot `--kill-on-exit` kills child JVMs** — bypass `JvmLauncher` with `exec java -cp` so the process replaces, not spawns
- **`< /dev/null`** fixes fd/0 but fd/1 and fd/2 still warn in background mode
- **proot-distro cleans up rootfs on non-zero exit** — `|| true` doesn't help; use manual rootfs extraction
- **`ls | head` under `set -o pipefail`** exits non-zero when no files match — needs `|| true`
- **DEBIAN_FRONTEND=noninteractive** required for apt-get in no-PTY mode

## Soft Guidelines

- Use `grep -Eo` (POSIX) not `grep -oP` (PCRE) for portability
- Termux uses toybox coreutils: `df -k` works, `df -m` does NOT
- `apt-get update` returns non-zero without `gpgv` — use `|| true`
- X11 socket bind-mount: `proot-distro login ubuntu --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"`
- `DISPLAY=:0` must be set inside proot after bind-mount

## Anti-Patterns

- Unescaped `"` inside `bash -c "..."` blocks
- `set -e` where proot exits non-zero harmlessly (use marker-based verification instead)
- Hardcoded `/tmp` paths (Termux uses `$PREFIX/tmp`)
- `systemctl` commands inside proot
- `mount` or FUSE-dependent tools inside proot
