# Installer Constraints

Per-package hard rules and soft guidelines derived from PackageInstaller gotchas.

## Hard Rules (Reject Proposal If Violated)

1. **Must fsync before session commit** — Data must be flushed to disk before calling `session.commit()`. Without fsync, the installer may read incomplete APK data and fail silently or install a corrupted package.

2. **Signing conflict detection required** — If Termux is installed from a different signing key (Play Store vs F-Droid vs GitHub), PackageInstaller will fail. The app must detect this and guide the user to uninstall the conflicting version first.

3. **STATUS_PENDING_USER_ACTION is not auto-complete** — The install intent must be presented to the user for explicit confirmation. Never assume the install will complete without user interaction.

4. **Session cleanup on failure** — Always abandon the PackageInstaller session in a `finally` block if the install fails or is cancelled. Accumulated abandoned sessions waste system resources.

5. **APK cache in app-private storage only** — Downloaded APKs must be stored in `getCacheDir()`, never external storage or `/sdcard/`.

## Soft Guidelines (Discuss Before Proceeding)

- Delete APK from cache after PackageInstaller session is opened (not after install completes)
- Verify package name via `PackageManager.getPackageArchiveInfo()` before installing
- GitHub Release asset URLs need `Accept: application/octet-stream` header
- Use HTTPS for all GitHub API calls; no `http://` fallback
- Temp APK file cleanup should be in a `finally` block or `use {}` — guaranteed even on failure

## Defect Patterns

- No active installer defects currently tracked

## Related Files

- `rules/installer.md` — path-triggered rule file
- `defects/_defects-installer.md` — active defect patterns
