# Authentication Solutions for Jagex Accounts

## Status: VERIFIED 2026-02-21

---

## CRITICAL CORRECTION: Environment Variables, Not CLI Args

The Jagex Launcher passes credentials via **environment variables**, NOT command-line arguments:

| Variable | Purpose |
|----------|---------|
| `JX_ACCESS_TOKEN` | OAuth2 access token |
| `JX_REFRESH_TOKEN` | OAuth2 refresh token |
| `JX_SESSION_ID` | Game session identifier — **reportedly does not expire** |
| `JX_CHARACTER_ID` | Which character/account to log in as |
| `JX_DISPLAY_NAME` | Character's display name |

Source: [RuneLite Wiki - Jagex Launcher Development](https://github.com/runelite/runelite/wiki/Jagex-Launcher-Development)

## KEY DISCOVERY: RuneLite's `--insecure-write-credentials` Flag

RuneLite has a **built-in development feature**: the `--insecure-write-credentials` CLI flag.
When set, RuneLite dumps auth tokens to `~/.runelite/credentials.properties`.
On subsequent launches, RuneLite reads this file and authenticates without the launcher.

**This means**: Once you get tokens ONCE, RuneLite can reuse them indefinitely (JX_SESSION_ID doesn't expire).

Source: [RuneLite.java](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/RuneLite.java)

## KEY DISCOVERY: Jagex Designed OAuth2 for Mobile

Jagex explicitly designed their OAuth2 system for mobile:
- **Android**: Uses **Trusted Web Activity** for OAuth2 browser flow
- **iOS**: Uses **ASWebAuthenticationSession**

This means we could implement the OAuth2 flow natively in our Android app using Android's standard OAuth libraries. The same tokens that work on desktop work on mobile.

Source: [Developer Diary: The Jagex Launcher](https://secure.runescape.com/m=news/a=12/developer-diary-the-jagex-launcher)

## OAuth2 Flow Details

- **Grant type**: `authorization_code`
- Launcher embeds Chromium browser for web login
- Standard web protections: Captcha, WAF, rate limiting
- Access tokens are short-lived, refresh tokens are longer-lived
- JX_SESSION_ID reportedly does NOT expire independently
- Launcher stores tokens for 30-day re-login

---

## Exact OAuth2 Endpoints (from rislah/jagex-launcher Go source)

### Step 1: Account Authentication
- Auth URL: `https://account.jagex.com/oauth2/auth`
- Token URL: `https://account.jagex.com/oauth2/token`
- Client ID: `com_jagex_auth_desktop_launcher`
- Redirect: `https://secure.runescape.com/m=weblogin/launcher-redirect`
- Scopes: `openid`, `offline`, `gamesso.token.create`, `user.profile.read`
- Uses PKCE with S256 method

### Step 2: Game Session Creation
- Same auth/token endpoints
- Client ID: `1fddee4e-b100-4f4e-b2b0-097f9088f9d2`
- Redirect: `http://localhost`
- Scopes: `openid`, `offline`

### Step 3: Get User Info & Sessions
- User info: `https://account.jagex.com/userinfo`
- Create game session: `https://auth.jagex.com/game-session/v1/sessions`
- Fetch characters: `https://auth.jagex.com/game-session/v1/accounts`

### Launch with 3 env vars:
```
JX_CHARACTER_ID=<value>
JX_DISPLAY_NAME=<value>
JX_SESSION_ID=<value>
```

Source: https://github.com/rislah/jagex-launcher

### Additional Auth Projects Found
- **rislah/jagex-launcher** (Go, cross-platform) — most complete endpoint docs
- **bb441db/auth-rs** (Rust) — another OAuth2 implementation
- **SteffenCarlsen/Jagex-Account-Switcher** (C#, Windows) — multi-account

---

## Solution 1: Native Android OAuth2 (NEW RECOMMENDED)

**Approach**: Implement the Jagex OAuth2 authorization_code flow directly in our Android app using Android's Trusted Web Activity or Custom Chrome Tabs.

**Why this is the best option**:
- Jagex explicitly designed their auth for this exact use case (Android Trusted Web Activity)
- No need for a separate launcher (Bolt, melxin, etc.)
- Tokens can be stored securely in Android Keystore
- JX_SESSION_ID doesn't expire — authenticate once, play indefinitely
- Set as environment variables when spawning RuneLite in proot

**What we need**:
- The OAuth2 endpoint URLs (reverse-engineered in melxin's Rust source code)
- The client_id and redirect_uri (also in melxin's source)
- Android Custom Chrome Tab or Trusted Web Activity for the browser step
- Secure token storage (Android Keystore)

## Solution 2: Bolt Launcher in proot

- **URL**: https://codeberg.org/Adamcake/Bolt
- Actively maintained, v0.21 Jan 2026, 1,365 commits
- C/C++ with Chromium Embedded Framework
- Could run in proot-distro Linux environment
- ARM64 support uncertain — needs CEF ARM64 build

## Solution 3: melxin/native-linux-jagex-launcher in proot

- **URL**: https://github.com/melxin/native-linux-jagex-launcher
- Rust — cross-compiles easily to aarch64
- Lightweight, no CEF dependency
- Uses xdg-open for browser + localhost:80 callback
- Could run inside proot, open Android browser for OAuth

## Solution 4: One-Time Desktop Token Extraction

- Use `--insecure-write-credentials` on desktop RuneLite once
- Copy `credentials.properties` to tablet
- RuneLite reads saved tokens on tablet — no launcher needed
- Simplest approach for personal use
- JX_SESSION_ID doesn't expire, so this is long-lived

## Solution 5: Kompreya/Runelite-Sans-Jagex-Launcher

- **URL**: https://github.com/Kompreya/Runelite-Sans-Jagex-Launcher
- Shell scripts to extract JX_* env vars from running Jagex Launcher
- Launch RuneLite with those variables

## Solution 6: Additional Tools

- **R3G3XR/RLaunch**: GUI launcher, captures credentials, multi-account support
  - https://github.com/R3G3XR/RLaunch
- **gavinnn101/jagex_account_launcher**: Python multi-account tool
  - https://github.com/gavinnn101/jagex_account_launcher

---

## Proot-Specific Constraints (IMPORTANT)

- **Flatpak, Snap, AppImage do NOT work in proot** — they require FUSE which proot can't provide
- This means: Bolt's Flatpak won't work, RuneLite's AppImage won't work
- **Solution**: Run RuneLite via `.jar` file directly with Java (works on any arch)
- **Solution**: Build Bolt or melxin's launcher from source as raw binaries (no packaging)
- Raw binaries and Java work fine in proot

## Bolt ARM64 Status

- **Flathub lists aarch64** — so ARM64 builds exist, but can't use Flatpak in proot
- **Can build from source** — CEF ARM64 Linux binaries available from [Spotify CDN](https://cef-builds.spotifycdn.com/index.html)
- Heavy dependency (~200MB+ for CEF) — may be impractical in proot

## RuneLite ARM64 Status

- Official ARM64 AppImage: `RuneLite-aarch64.AppImage` (can't use AppImage in proot)
- **RuneLite .jar works on any arch with JRE** — this is how we run it in proot
- Build script exists: [build-linux-aarch64.sh](https://github.com/Rune-Status/runelite-launcher/blob/master/build-linux-aarch64.sh)

---

## Recommendation for Tablet Project

### Phase 1 (MVP): One-time desktop token extraction
- User runs RuneLite on desktop with `--insecure-write-credentials` once
- Copies `credentials.properties` to tablet (or app provides a transfer mechanism)
- RuneLite on tablet reads saved tokens
- Simplest, no reverse engineering needed, works today

### Phase 2: Native Android OAuth2
- Implement Jagex OAuth2 flow in the Android app
- Use melxin's Rust source as reference for endpoint URLs
- Android Trusted Web Activity for browser login
- Store tokens in Android Keystore
- Full self-contained experience — no desktop needed

### Phase 3 (if needed): Bolt integration
- Compile/run Bolt in proot as a fallback auth method
- Most mature implementation, handles edge cases

---

## Official Jagex Sources
- [Jagex Launcher - Technical Newspost](https://secure.runescape.com/m=news/jagex-launcher---technical-newspost?oldschool=1)
- [Developer Diary: The Jagex Launcher](https://secure.runescape.com/m=news/a=12/developer-diary-the-jagex-launcher)
- [Jagex Launcher FAQ](https://help.jagex.com/hc/en-gb/articles/17160273294097-Jagex-Launcher-FAQ)

## RuneLite Sources
- [RuneLite Wiki - Using Jagex Accounts](https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts)
- [RuneLite Wiki - Jagex Launcher Development](https://github.com/runelite/runelite/wiki/Jagex-Launcher-Development)
- [RuneLite.java](https://github.com/runelite/runelite/blob/master/runelite-client/src/main/java/net/runelite/client/RuneLite.java)

## Community Implementations
- [melxin/native-linux-jagex-launcher](https://github.com/melxin/native-linux-jagex-launcher) (Rust, full OAuth2)
- [aitoiaita/linux-jagex-launcher](https://github.com/aitoiaita/linux-jagex-launcher) (Rust, full OAuth2)
- [Bolt Launcher](https://codeberg.org/Adamcake/Bolt) (C/C++, CEF-based)
- [Kompreya/Runelite-Sans-Jagex-Launcher](https://github.com/Kompreya/Runelite-Sans-Jagex-Launcher) (Shell scripts)
- [R3G3XR/RLaunch](https://github.com/R3G3XR/RLaunch) (GUI launcher)
- [gavinnn101/jagex_account_launcher](https://github.com/gavinnn101/jagex_account_launcher) (Python)
