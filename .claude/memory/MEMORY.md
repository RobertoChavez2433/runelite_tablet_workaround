# Memory: Runelite for Tablet

## Project Overview
- Tablet-optimized way to run the REAL RuneLite on Samsung Tab S10 Ultra (Snapdragon 8 Gen 3, 12-16GB RAM)
- NOT a RuneLite clone/port — runs actual RuneLite .jar with all plugins
- User plays with physical mouse + keyboard via Samsung DeX

## Architecture Decision
- Android installer app (Kotlin/Jetpack Compose) that automates Termux + proot-distro + RuneLite setup
- RuneLite runs as `.jar` inside proot Ubuntu ARM64 with OpenJDK 11
- Display via Termux:X11; Audio via PulseAudio
- AppImage/Flatpak/Snap do NOT work in proot (no FUSE)

## Slice 1 Implementation
- Source code at `runelite-tablet/` — full Android project
- 15 Kotlin files + 2 shell scripts, APK builds clean
- Key packages: termux/, installer/, setup/, ui/
- Manual DI (no Hilt/Koin), single-screen (no Navigation)
- SetupActions callback pattern (not direct Activity ref) to avoid leaks
- ViewModelProvider.Factory + `by viewModels{}` for lifecycle safety
- AtomicInteger for Termux execution IDs (not nanoTime)
- Known bug: CancellationException swallowed in SetupOrchestrator catch blocks

## .claude System Redesign (approved, not yet implemented)
- Design doc: `.claude/plans/2026-02-22-claude-system-redesign.md`
- 2 Opus agents: code-review-agent (10-category checklist), performance-agent (6-category full-stack)
- /implement skill: orchestrator dispatching Sonnet implementers + Opus code review loops
- /systematic-debugging skill: 4-phase root cause analysis with pressure tests
- Upgraded brainstorming, resume-session, end-session skills
- Pattern adopted from Hiscores Tracker (battle-tested across 17 sessions, 7 PRs)

## Auth: Key Facts
- Jagex Launcher passes creds via env vars: `JX_SESSION_ID`, `JX_CHARACTER_ID`, `JX_DISPLAY_NAME`, `JX_ACCESS_TOKEN`, `JX_REFRESH_TOKEN`
- `JX_SESSION_ID` does NOT expire
- RuneLite `--insecure-write-credentials` flag saves tokens to `~/.runelite/credentials.properties`
- Jagex OAuth2 supports Android Trusted Web Activity natively
- Exact endpoints: auth `https://account.jagex.com/oauth2/auth`, token `https://account.jagex.com/oauth2/token`
- Full endpoint docs: `.claude/research/authentication-solutions.md`

## GPU: Key Facts
- RuneLite GPU plugin needs OpenGL 4.3+ (compute shaders) or 4.0 (no compute)
- Mesa Zink translates OpenGL → Vulkan; achieves OpenGL 4.6 on Android
- Zink + Turnip (open-source Adreno Vulkan driver) = best performance path
- GL4ES maxes at OpenGL 2.1 — not viable; VirGL alone = 1-2 FPS — not viable
- Software rendering (50fps) works fine as MVP

## Research Files
All in `.claude/research/`: jagex-launcher-auth.md, authentication-solutions.md, runelite-architecture.md, android-approaches.md, gpu-rendering-options.md, existing-projects.md

## User Preferences
- Wants thorough PRD with lots of back-and-forth
- Prefers agents for research to preserve context
- Moderate tech comfort — automate but allow troubleshooting
- Start personal, grow to distributable later
- Wants full implementation done in one pass (dispatched agents for parallel work)
- Both agents should use Opus model (not Sonnet)

## Operational Notes
- Background agents may get blocked by file write permissions — write files directly instead
- Always create placeholder launcher icons when scaffolding Android projects
- Gradle wrapper jar must be downloaded (not available via `gradle` CLI on this system)
