# Termux Shell Agent Memory

## Termux RUN_COMMAND Facts

- Result Bundle key: `intent.getBundleExtra("result")` — NOT flat extras
- Inside Bundle: `"stdout"` (String), `"stderr"` (String), `"exitCode"` (Int), `"err"` (Int), `"errmsg"` (String)
- `"err"` value of `-1` means no error (Termux sentinel) — only `errCode > 0` is real error
- `execution_id` is a top-level intent extra (not inside the Bundle)
- PendingIntent MUST be FLAG_MUTABLE — fill-in extras silently dropped with IMMUTABLE

## Termux Path Conventions

- Bash: `/data/data/com.termux/files/usr/bin/bash`
- `$PREFIX` = `/data/data/com.termux/files/usr`
- `$HOME` = `/data/data/com.termux/files/home`
- `$PREFIX/tmp` is real tmp dir (NOT `/tmp`)
- X11 socket: `$PREFIX/tmp/.X11-unix/X0`
- toybox coreutils: `df -k` works, `df -m` does NOT

## Proot Facts

- proot warns about `/proc/self/fd/0,1,2` with no PTY — non-zero exit on success
- proot-distro self-deletes rootfs on non-zero exit — use manual extraction
- `--kill-on-exit` kills child processes — use `exec java -cp` to replace process
- Manual rootfs: `proot --link2symlink tar xf TARBALL -C ROOTFS --strip-components=1`
- Must create `.l2s` directory for link2symlink
- Post-extraction config: resolv.conf, hosts, environment
- DEBIAN_FRONTEND=noninteractive for apt-get

## X11 Facts

- Bind-mount: `--bind "$PREFIX/tmp/.X11-unix:/tmp/.X11-unix"`
- `DISPLAY=:0` inside proot after bind-mount
- Termux:X11 requires `x11-repo` package first

## GPU Facts

- Mali-G720 (NOT Adreno) — Zink+Turnip is Adreno-only
- VirGL is only viable GPU path for Mali in proot
- lfdevs Mesa provides virtio_gpu_dri.so (standard Ubuntu Mesa lacks it)
- GPU tiers: VirGL+ANGLE > VirGL+GLES > llvmpipe (software)
- VirGL server polling: 2s max, tied to session lifecycle

## Shell Script Patterns

- All scripts: `set -euo pipefail`
- All `"` inside `bash -c "..."` must be `\"`
- Windows CRLF breaks shebangs — `.gitattributes` eol=lf
- Use `grep -Eo` (POSIX) not `grep -oP` (PCRE)
- `apt-get update` may fail without gpgv — use `|| true`
- `ls | head` under pipefail needs `|| true`
