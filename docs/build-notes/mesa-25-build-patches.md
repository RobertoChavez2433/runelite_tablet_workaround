# Mesa 25.2.8 rebuild — patches applied to termux-packages

Clone: `third_party/termux-packages/` (not checked into our repo; shallow clone from https://github.com/termux/termux-packages.git at ~S78 master).

## Patch 1: `packages/mesa/build.sh`

Pin version to Mesa 25.2.8 (the last known version whose GLX client accepts Termux:X11's fakeGLX protocol — 26.0.5 regressed it, see docs/logs/phase-2.2-diag-preflight-evidence.log).

```diff
-TERMUX_PKG_VERSION="26.0.5"
-TERMUX_PKG_SRCURL=https://archive.mesa3d.org/mesa-${TERMUX_PKG_VERSION}.tar.xz
-TERMUX_PKG_SHA256=d229c9937d9a25ca0a8958c59f425174563d300ec42acbea2dbe84a055023368
-TERMUX_PKG_AUTO_UPDATE=true
+TERMUX_PKG_VERSION="25.2.8"
+TERMUX_PKG_SRCURL=https://archive.mesa3d.org/mesa-${TERMUX_PKG_VERSION}.tar.xz
+TERMUX_PKG_SHA256=097842f3e49d996868b38688db87b006f7d4541e93ce86d2f341d8b3e7be7c93
+TERMUX_PKG_AUTO_UPDATE=false
```

## Patch 2: `scripts/run-docker.sh`

On Git-Bash for Windows, `--device /dev/fuse` gets path-mangled into `C:/dev/fuse` and docker rejects with `error gathering device information while adding custom device "C"`. AppArmor isn't available either. Drop both safety-layers on non-Linux hosts — the seccomp profile pinned by each recipe stays in effect.

```diff
 UNAME=$(uname)
 if [ "$UNAME" = Darwin ]; then
 	REPOROOT=$PWD
 	SEC_OPT=""
+elif [[ "$UNAME" == MINGW* || "$UNAME" == MSYS* || "$UNAME" == CYGWIN* ]]; then
+	REPOROOT=$PWD
+	SEC_OPT=" --cap-add CAP_SYS_ADMIN"
 else
 	REPOROOT="$(dirname $(readlink -f $0))/../"
 	SEC_OPT=" --security-opt seccomp=$REPOROOT/scripts/profile.json --security-opt apparmor=_custom-termux-package-builder-$CONTAINER_NAME --cap-add CAP_SYS_ADMIN --device /dev/fuse"
 fi
```

## Rebuild steps

```bash
cd third_party/termux-packages
MSYS2_ARG_CONV_EXCL='*' ./scripts/run-docker.sh ./build-package.sh -a aarch64 mesa
# Output: third_party/termux-packages/output/mesa_25.2.8_aarch64.deb
#         (plus mesa-dev, mesa-vulkan-icd-wrapper, and any sub-packages)
```

## Deploy steps

```bash
./scripts/deploy-mesa25-to-device.sh R52X90378YB
```
