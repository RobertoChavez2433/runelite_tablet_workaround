# Security Feature Overview

## Purpose

Security hardening across all packages. Not a single source package — security concerns cut across auth, setup, termux, and shell layers. This document covers credential storage, shell injection prevention, IPC security, and process death handling.

## Key Areas

- **Credential storage** — EncryptedSharedPreferences backed by Android Keystore; corruption recovery to prevent permanent brick
- **Shell injection prevention** — shellEscape in SetupViewModel must cover all 5 bash metacharacters plus newlines; credentials passed via temp env file, never CLI args
- **IPC security** — FLAG_MUTABLE required on Termux PendingIntents; exported components locked down; execution IDs validated
- **Process death handling** — GeckoAuthActivity must detect restored state and finish with error; GeckoView sessions cannot survive process death

## Key Files

| File | Security Concern |
|------|-----------------|
| `auth/CredentialManager.kt` | EncryptedSharedPreferences storage, corruption recovery |
| `setup/SetupViewModel.kt` | shellEscape function — metacharacter and newline coverage |
| `auth/GeckoAuthActivity.kt` | FLAG_SECURE, process death check, savedInstanceState guard |

## Related

- Constraints: `architecture-decisions/security-constraints.md`
- Rules: `rules/auth.md`
- Defects: `defects/_defects-security.md`
- Agent: `agents/security-review-agent.md`
