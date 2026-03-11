# Installer Feature Architecture

## Component Design

```
ApkDownloader
  ├── OkHttpClient (connection pool, timeouts)
  ├── download(url) → File in getCacheDir()
  ├── GitHub API: Accept: application/octet-stream header
  └── HTTPS only, no http:// fallback

ApkInstaller
  ├── PackageInstaller.Session
  ├── openSession() → write APK data with fsync
  ├── commit() → triggers user confirmation
  └── abandon() → cleanup on failure (finally block)

InstallResultReceiver (BroadcastReceiver)
  ├── STATUS_SUCCESS → install complete
  ├── STATUS_PENDING_USER_ACTION → launch confirmation intent
  ├── STATUS_FAILURE → report error
  └── exported="false" (internal only)
```

## Installation Flow

```
ApkDownloader.download()
  → File saved to getCacheDir()
    → ApkInstaller.openSession()
      → Write APK data to session
        → session.fsync()
          → session.commit()
            → User sees confirmation dialog
              → InstallResultReceiver.onReceive()
                → Complete/Fail
                  → Delete cached APK
```

## Key Design Decisions

- **getCacheDir() for APK storage** — app-private, cleaned on uninstall, never external storage
- **fsync before commit** — ensures data integrity before PackageInstaller reads the APK
- **Session cleanup in finally** — abandoned sessions cleaned up even on crash
- **Package name verification** — `getPackageArchiveInfo()` check before installing
- **Signing conflict detection** — guide user to uninstall conflicting Termux version

## Threading

| Operation | Dispatcher |
|-----------|-----------|
| APK download | IO (blocking network I/O) |
| Session write | IO (blocking file I/O) |
| Install result | Main (BroadcastReceiver) |
