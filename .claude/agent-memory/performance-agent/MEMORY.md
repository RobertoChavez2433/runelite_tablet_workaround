# Performance Agent Memory

## Known Performance Concerns

| Issue | Severity | Location | Status |
|-------|----------|----------|--------|
| OkHttp `.execute()` blocks IO dispatcher, not cancellation-aware | MEDIUM | ApkDownloader | Open |
| No retry for transient GitHub API failures (502/503) | MEDIUM | ApkDownloader | Open |
| `apt-get update` always re-runs on retry (~30s waste) | LOW | setup-environment.sh | Open |
| Hardcoded `sleep 2` for X11 startup (may be insufficient/excessive) | LOW | launch-runelite.sh | Open |
| No disk space check before downloads | MEDIUM | setup-environment.sh | 512MB check added (Session 38) |
| pendingResults map not cleaned on timeout | LOW | TermuxResultService | Open |

## Baseline Performance Data

- proot operations: 8-10 min for rootfs extraction + Java install (ptrace overhead, normal)
- RuneLite software rendering: ~50fps cap (CPU-bound via llvmpipe)
- VirGL + Mesa: GPU-accelerated, needs testing after auth fix
- X11 socket bind-mount overhead: negligible

## Architecture Performance Notes

- Single-screen Compose app — minimal recomposition overhead
- StateFlow pipeline is straightforward (no complex derivations)
- Manual DI means no runtime reflection overhead
- Sequential setup steps — no parallelization possible (each depends on prior)

## Calibration Notes

- proot inherently adds ptrace overhead to every syscall — this is architectural, not a bug
- GPU tiered fallback adds startup latency but is necessary for reliability
- VirGL server polling (2s max) is acceptable tradeoff for reliability
