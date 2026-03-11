# Shell Feature Architecture

## Environment Stack

```
Android (Samsung Tab S10 Ultra)
  └── Termux (terminal emulator, pkg manager)
      ├── proot-distro (rootfs manager, ptrace-based)
      │   └── Ubuntu ARM64 (rootfs)
      │       ├── OpenJDK 11
      │       ├── Mesa (lfdevs build for virpipe support)
      │       └── RuneLite .jar
      ├── Termux:X11 (X11 server)
      ├── PulseAudio (audio via TCP)
      └── VirGL server (GPU translation layer)
```

## GPU Tiered Fallback

| Tier | Path | GL Version | Performance |
|------|------|-----------|-------------|
| 1 | VirGL + ANGLE | OpenGL 3.1+ | Best (GPU-accelerated) |
| 2 | VirGL + native GLES | OpenGL 3.1+ | Good (GPU-accelerated) |
| 3 | llvmpipe (software) | OpenGL 4.5 | CPU-bound, ~50fps cap |

## Key Design Decisions

- **Manual rootfs extraction** — proot-distro install is unreliable (exit codes lie, self-deletes on failure). Manual `proot --link2symlink tar xf` is more reliable.
- **`exec java -cp`** — Replaces shell process with Java instead of spawning child. Prevents proot `--kill-on-exit` from killing the JVM.
- **VirGL for Mali GPU** — Zink+Turnip is Adreno-only. VirGL is the only viable GPU path for Mali-G720 in proot.
- **lfdevs Mesa** — Standard Ubuntu ARM64 Mesa lacks `virtio_gpu_dri.so`. lfdevs Mesa provides virpipe driver support.
- **Marker-based verification** — Never trust exit codes from proot. Use `which java`, file existence checks, etc.

## Proot Constraints

- No FUSE — AppImage/Flatpak/Snap won't work
- No systemd — can't use `systemctl`
- No mount — filesystem operations limited
- `/proc/self/fd` warnings are normal (non-zero exit doesn't mean failure)
- `DEBIAN_FRONTEND=noninteractive` required for apt-get

## X11 Forwarding

```
Termux:X11 runs X server at $PREFIX/tmp/.X11-unix/X0
  ↓ bind-mount
proot --bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"
  ↓ inside proot
DISPLAY=:0 → connects to Termux:X11
```
