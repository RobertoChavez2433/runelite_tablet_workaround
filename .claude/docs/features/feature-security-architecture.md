# Security Feature Architecture

## Component Design

```
CredentialManager
  ├── EncryptedSharedPreferences (AES256_GCM via Android Keystore)
  ├── save(credentials) → persist tokens to encrypted store
  ├── load() → retrieve tokens
  ├── clear() → wipe all credentials
  └── handleCorruption() → delete corrupted file + retry once (guarded by flag)

SetupViewModel.shellEscape(value: String)
  ├── Strip \n, \r, \0 (newlines break out of quoted strings entirely)
  ├── Escape \ → \\
  ├── Escape " → \"
  ├── Escape $ → \$
  ├── Escape ` → \`
  ├── Escape ! → \!
  └── Wrap result in double quotes for env file assignment

GeckoAuthActivity
  ├── onCreate: check savedInstanceState != null → finish with error (process death guard)
  ├── window.addFlags(FLAG_SECURE) → block recents screenshot
  ├── GeckoView session (not WebView — Cloudflare blocks WebView)
  └── NavigationDelegate → intercept jagex: URI, extract auth code

Termux PendingIntent Pattern
  ├── FLAG_MUTABLE required — FLAG_IMMUTABLE silently drops result extras
  ├── Explicit component name set on result intent
  └── Execution ID validated in TermuxResultService before dispatching result
```

## EncryptedSharedPreferences Corruption Recovery

```
create() call
  └── GeneralSecurityException thrown (corrupted XML or Keystore unavailable)
        ├── if prefsRecreateAttempted → throw (prevent infinite loop)
        ├── prefsRecreateAttempted = true
        ├── delete corrupted file from getFilesDir()
        └── retry create() once → success or propagate exception
```

## Shell Escaping Strategy

Double-quote escaping is used instead of single-quote escaping because single-quote escaping (`'\''`) does not protect against `$()` subshell injection and backtick expansion when the env file is `source`d inside bash. The five metacharacters that must be escaped are `\`, `"`, `$`, `` ` ``, `!`. Newlines (`\n`, `\r`, `\0`) must be stripped entirely — they cannot be safely escaped inside a quoted string and will break out of the assignment.

Alternative: `printf %q` (bash-native, handles all metacharacters) can be used inside shell scripts when the escaping is done at script-generation time.

## IPC Security Model

| Component | Security Control | Reason |
|-----------|----------------|--------|
| Termux PendingIntent | FLAG_MUTABLE | Result extras require runtime write-back |
| TermuxResultService | exported="false" | Prevents rogue apps sending fake results |
| InstallResultReceiver | exported="false" | Prevents install result spoofing |
| Execution ID map | Validate in ConcurrentHashMap | Discard unknown/stale IDs immediately |
| GeckoAuthActivity redirect | Validate host + scheme | Reject unexpected redirect URIs |

## Process Death Handling in GeckoAuthActivity

GeckoView sessions are not Parcelable and cannot survive Android process death. `lateinit` vars in GeckoAuthActivity become uninitialized if the Activity is recreated from a saved instance state. The guard pattern:

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    if (savedInstanceState != null) {
        // Process was killed and Activity is being restored — GeckoView cannot recover
        setResult(RESULT_CANCELED)
        finish()
        return
    }
    window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    // ... normal init
}
```

## Key Design Decisions

- **Double-quote escaping over single-quote** — Single-quote pattern misses `$()` and backtick when file is `source`d; double-quote escaping with explicit metacharacter list is safe and auditable.
- **Corruption recovery with guard flag** — Retry once on `GeneralSecurityException` prevents infinite loop; without guard, a second exception re-enters the same handler.
- **FLAG_MUTABLE on Termux PendingIntents** — This is a hard requirement of the RUN_COMMAND protocol; changing to FLAG_IMMUTABLE silently breaks result delivery with no error.
- **Process death check before GeckoView init** — GeckoView allocates a heavyweight process; finishing early on restored state avoids a crash and returns a clean RESULT_CANCELED to the caller.

## Related

- Security review agent: `agents/security-review-agent.md`
- Constraint file: `architecture-decisions/security-constraints.md`
- Defects: `defects/_defects-security.md`
