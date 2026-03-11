# Security Review Agent Memory

## Accepted Risks for MVP (Personal Use)

| Risk | Accepted Mitigation | Escalate When |
|------|--------------------|----|
| No TLS pinning (Jagex, GitHub) | HTTPS + system CAs sufficient for personal use | Moving to distribution |
| No APK checksum/signature verification | GitHub HTTPS + open-source content | Distribution or untrusted networks |
| Sequential execution IDs (AtomicInteger) | IDs consumed immediately; narrow window | Distribution (switch to UUID) |
| proot not a kernel-level sandbox | Android file permissions still apply | N/A (architectural constraint) |
| `access_token` may be persisted | Should verify it's in-memory only | Always |
| RuneLite plugin ecosystem untrusted | Out of scope for app-level review | N/A |

## Security Fixes Applied

- Credential transport via temp env file (not CLI args) — prevents `ps` exposure
- EncryptedSharedPreferences with AES256_GCM via Android Keystore
- Shell escape function covers all bash metacharacters (after Session 24 fix)
- GeckoAuthActivity process death handling planned (savedInstanceState check)
- FLAG_SECURE on auth activities planned
- InstallResultReceiver and TermuxResultService `exported="false"`
- Token toString() returns `"[REDACTED]"`

## Open Security Issues

- shellEscape must cover ALL metacharacters including newlines (defect still open)
- EncryptedSharedPreferences corruption handling needs implementation
- GeckoAuthActivity process death check needs implementation
- Pre-launch session validation needs implementation

## Calibration Notes

- Threat model: personal device, no distribution — adjust severity accordingly
- Flag MVP-level risks as LOW/INFO, not blocking
- Network security config should have `cleartextTrafficPermitted="false"`
