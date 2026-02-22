# Jagex Launcher Authentication Flow

## Status: VERIFIED 2026-02-21

## CORRECTION: Environment Variables, Not CLI Args

The Jagex Launcher passes credentials via **environment variables** (not command-line arguments):

| Variable | Purpose |
|----------|---------|
| `JX_ACCESS_TOKEN` | OAuth2 access token for API calls |
| `JX_REFRESH_TOKEN` | OAuth2 refresh token for obtaining new access tokens |
| `JX_SESSION_ID` | Game session identifier — **does not expire** |
| `JX_CHARACTER_ID` | Which character/account to use |
| `JX_DISPLAY_NAME` | Character's display name |

## OAuth2 Flow

1. Jagex Launcher uses **OAuth2 authorization_code** grant type
2. Launcher embeds Chromium browser for web-based login at Jagex's auth pages
3. Standard protections: Captcha, WAF, rate limiting
4. On success, receives Access Token + Refresh Token
5. Tokens stored securely on disk by the launcher
6. When launching a game client, launcher sets `JX_*` environment variables
7. Game client reads env vars and authenticates with Jagex game servers

## RuneLite Integration

- RuneLite reads `JX_*` environment variables on startup
- Main entry: `runelite-client/src/main/java/net/runelite/client/RuneLite.java`
- RuneLite does NOT independently authenticate — passive recipient of tokens
- **`--insecure-write-credentials` flag**: Dumps tokens to `~/.runelite/credentials.properties`
- Once saved, RuneLite can reuse tokens without the launcher (JX_SESSION_ID doesn't expire)

## Mobile OAuth2 Support (KEY FINDING)

Jagex explicitly designed their OAuth2 for mobile platforms:
- **Android**: Trusted Web Activity
- **iOS**: ASWebAuthenticationSession
- Same tokens work across all platforms

Source: [Developer Diary: The Jagex Launcher](https://secure.runescape.com/m=news/a=12/developer-diary-the-jagex-launcher)

## Standalone Launch Options

- **Legacy accounts**: RuneLite launches standalone, user enters username/password
- **Jagex accounts with saved credentials**: Use `--insecure-write-credentials` once, then RuneLite reuses the saved tokens
- **Native OAuth2**: Implement the authorization_code flow directly (melxin's Rust code has the endpoints)

## Verified Sources

- [RuneLite Wiki - Jagex Launcher Development](https://github.com/runelite/runelite/wiki/Jagex-Launcher-Development)
- [RuneLite Wiki - Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
- [Jagex Launcher Technical Newspost](https://secure.runescape.com/m=news/jagex-launcher---technical-newspost?oldschool=1)
- [Developer Diary](https://secure.runescape.com/m=news/a=12/developer-diary-the-jagex-launcher)
- [RuneLite.java source](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/RuneLite.java)
