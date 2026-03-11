# Installer Feature Overview

## Purpose

The Installer layer handles downloading and installing Termux APK packages from GitHub Releases. It uses Android's PackageInstaller session API for secure installation.

## Key Capabilities

- **Download APKs** from GitHub Releases via OkHttp
- **Install APKs** via PackageInstaller session API
- **Handle user confirmation** for STATUS_PENDING_USER_ACTION
- **Detect signing conflicts** when different Termux builds are installed

## How It Works

1. Check if Termux is already installed (TermuxPackageHelper)
2. If not, download the Termux APK from GitHub Releases via HTTPS
3. Create a PackageInstaller session
4. Write APK data to session with fsync
5. Commit session — user sees install confirmation dialog
6. Receive install result via BroadcastReceiver
7. Clean up APK cache file

## Key Files

| File | Role |
|------|------|
| `ApkDownloader.kt` | Download APKs via OkHttp from GitHub Releases API |
| `ApkInstaller.kt` | Install APKs via PackageInstaller session API |
| `InstallResultReceiver.kt` | Receive install status via BroadcastReceiver |

## Related

- Constraints: `architecture-decisions/installer-constraints.md`
- Rules: `rules/installer.md`
- Defects: `defects/_defects-installer.md`
