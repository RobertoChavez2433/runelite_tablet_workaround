# Security Defects

Max 5 active. Oldest rotates to `.claude/logs/defects-archive.md`.

## Active Patterns

### [SECURITY] 2026-02-24: shellEscape must cover ALL bash metacharacters including newlines
**Pattern**: Single-quote escaping (`'\''`) misses `$()`, backtick, and other expansions when file is `source`d. Newlines in credential values break out of quoted strings entirely, enabling shell injection.
**Prevention**: Use double-quote escaping for all 5 metacharacters (`\`, `"`, `$`, `` ` ``, `!`) PLUS strip `\n`, `\r`, `\0`. Or use `printf %q` (bash-native).
**Ref**: @runelite-tablet/app/src/main/java/com/runelitetablet/setup/SetupViewModel.kt (shellEscape)
