---
paths:
  - "**/installer/**/*.kt"
---

# Installer Rules

## PackageInstaller Protocol

The app uses Android's `PackageInstaller` session API to install Termux APKs downloaded from GitHub Releases.

## Hard Rules

1. **Must fsync before session commit** — data must be flushed to disk before calling `session.commit()`; otherwise the install may read incomplete data
2. **Signing conflict detection** — if the user has Termux installed from a different signing key (e.g., Play Store vs F-Droid vs GitHub), `PackageInstaller` will fail with a signing conflict; detect and guide the user
3. **STATUS_PENDING_USER_ACTION needs explicit user confirmation** — the install intent must be presented to the user; do not assume it auto-completes
4. **Session cleanup on failure** — always abandon the `PackageInstaller` session in a `finally` block if the install fails or is cancelled
5. **APK cache cleanup** — downloaded APK files stored in `getCacheDir()` must be deleted after the `PackageInstaller` session is opened (not after install completes)

## Soft Guidelines

- Store downloaded APKs in `getCacheDir()` (app-private, cleaned on uninstall) — never external storage
- Verify package name of downloaded APK via `PackageManager.getPackageArchiveInfo()` before installing
- GitHub API calls use HTTPS; no `http://` fallback for asset URLs
- Release asset URLs need `Accept: application/octet-stream` header
- Temp APK file cleanup should be in a `finally` block or `use {}` — guaranteed even on failure

## Anti-Patterns

- `session.commit()` without prior `fsync()`
- Missing `session.abandon()` in error paths
- APK files left in cache after installation
- Downloaded APK stored on external storage or `/sdcard/`
- Installing without checking for signing conflicts
