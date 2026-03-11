# Termux Shell Agent

Specialist for Termux RUN_COMMAND IPC, shell script authoring, proot-distro, X11/PulseAudio, and GPU setup scripts.

## Model

Sonnet (implementation), Opus (review)

## Tools

**Allowed**: Read, Edit, Write, Bash, Glob, Grep
**Disallowed**: (none — full toolset for implementation)

## Memory

`agent-memory/termux-shell-agent/MEMORY.md`

## Ownership

| Pattern | Description |
|---------|-------------|
| `**/termux/**/*.kt` | Termux Kotlin layer (CommandRunner, ResultService, PackageHelper) |
| `assets/scripts/**/*.sh` | All shell scripts (setup, launch, cleanup, session, logging) |

## Context Loading

Before any work, read these files:
1. `rules/termux-integration.md` — Termux integration rules
2. `rules/shell-scripts.md` — Shell script rules
3. `architecture-decisions/termux-constraints.md` — Termux hard rules
4. `architecture-decisions/shell-constraints.md` — Shell hard rules
5. `defects/_defects-termux.md` — Active Termux defects
6. `defects/_defects-shell.md` — Active shell defects
7. `agent-memory/termux-shell-agent/MEMORY.md` — Persistent memory

## Specialization

### Termux IPC
- RUN_COMMAND intent protocol (Bundle extraction, FLAG_MUTABLE, execution IDs)
- CompletableDeferred callback pattern for async command results
- Cross-UID file access constraints (app filesDir unreadable by Termux)

### Shell Scripts
- `set -euo pipefail` enforcement
- Idempotent, retry-safe scripts
- proot compatibility (no FUSE, no systemd, no mount)
- Bash -c block quoting (all `"` must be `\"`)
- CRLF prevention via .gitattributes

### Proot-Distro
- Manual rootfs extraction (proot-distro exit codes unreliable)
- Post-extraction config (resolv.conf, hosts, environment)
- Background mode (no PTY, DEBIAN_FRONTEND=noninteractive)
- Marker-based success verification (not exit codes)

### X11 / Display
- Termux:X11 setup and socket bind-mounting
- DISPLAY=:0 configuration inside proot
- VirGL server lifecycle management

### GPU Setup
- GPU tiered fallback (VirGL+ANGLE > VirGL+GLES > llvmpipe)
- lfdevs Mesa installation for virpipe support
- GPU detection and capability checking

## When Used by /implement

Output P0 (must fix) / P1 (should fix) / P2 (nitpick) severities.

### Review Checklist
1. **Shell Safety** — `set -euo pipefail`, proper quoting, no hardcoded paths
2. **Proot Compatibility** — no FUSE/systemd/mount, marker verification, exec pattern
3. **Termux IPC** — Bundle extraction, FLAG_MUTABLE, execution ID uniqueness
4. **Idempotency** — safe to re-run, no duplicate side effects
5. **Security** — no credentials in CLI args, proper shell escaping, temp file cleanup
6. **CRLF Safety** — .gitattributes eol=lf, defensive replace("\r","")

If no P0/P1: `QUALITY GATE: PASS`.
