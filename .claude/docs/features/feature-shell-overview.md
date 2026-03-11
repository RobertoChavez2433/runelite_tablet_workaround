# Shell Feature Overview

## Purpose

The Shell layer provides the Linux environment setup and RuneLite launch scripts that run inside Termux's proot-distro Ubuntu environment.

## Key Scripts

| Script | Purpose |
|--------|---------|
| `setup-environment.sh` | Install proot-distro, Ubuntu ARM64, OpenJDK 11, dependencies |
| `launch-runelite.sh` | Start RuneLite .jar with X11 display forwarding and GPU config |

## How It Works

### Environment Setup
1. Install proot-distro in Termux
2. Extract Ubuntu ARM64 rootfs (manual extraction for reliability)
3. Configure rootfs: resolv.conf, hosts, environment
4. Install OpenJDK 11 inside proot Ubuntu
5. Install dependencies (Mesa, X11 libraries, PulseAudio)

### RuneLite Launch
1. Start VirGL server (if GPU available)
2. Configure X11 display forwarding via bind-mount
3. Source credential env file (then delete it)
4. Launch RuneLite via `exec java -cp` (not JvmLauncher)
5. GPU tiered fallback: VirGL+ANGLE (Tier 1) > VirGL+GLES (Tier 2) > Software (Tier 3)

## Key Facts

- proot operations are slow (ptrace overhead): 8-10 min for setup is normal
- X11 socket must be bind-mounted: `--bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"`
- `DISPLAY=:0` must be set inside proot
- `exec java -cp` replaces process to avoid proot `--kill-on-exit` killing child JVMs

## Related

- Constraints: `architecture-decisions/shell-constraints.md`
- Rules: `rules/shell-scripts.md`
- Defects: `defects/_defects-shell.md`
