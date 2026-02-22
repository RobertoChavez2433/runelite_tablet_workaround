# Existing Projects: RuneLite on Android/Mobile

## Status: Researched 2026-02-21

---

## 1. MeteorLite/meteor-mobile (MOST PROMISING)
- **URL**: https://github.com/MeteorLite/meteor-mobile
- **Approach**: Native Android port of the Meteor client (RuneLite-based OSRS client)
- **Architecture**:
  - Injection-based: takes OSRS client and injects RuneLite-style API
  - Custom AWT implementation for Android rendering
  - Modules: `app-android`, `app-common`, `app-desktop`, `api`, `api-rs`, `osrs`, `awt`, `injector`, `mixins`, `deobfuscator`, `cache`, `eventbus`
  - Languages: Java (88.5%), Kotlin (6.1%), C (5.0%)
- **Features**: Full AWT software rendering (50fps), login/world interaction, reflection checks, touch + keyboard support
- **Status**: 47 commits, appears sporadically maintained
- **Key insight**: Full working proof-of-concept that RuneLite-style functionality CAN work on Android
- **Limitation**: Mainly targets RSPS (private servers), but connects to official OSRS by default
- **Build**: Requires Android Studio/NDK Canary, Android 8.0+ (API 26)

## 2. open-osrs/openosrs-mobile
- **URL**: https://github.com/open-osrs/openosrs-mobile
- **Approach**: Injection-based — takes vanilla OSRS APK, injects RuneLite API code
- **Architecture**:
  - Deobfuscator processes game packets
  - Separate modules: client, injector, RuneLite API, RuneScape API, HTTP API
  - Gradle-based build
- **Status**: 20 commits, 13 stars, 8 forks — ABANDONED/stale (no updates in 6+ months)
- **Build**: Requires Android SDK 26 & 29, vanilla OSRS APK v194
- **Key insight**: Demonstrated the injection approach works but needs significant refactoring

## 3. MeteorLite/hotlite-client (AUTH BYPASS)
- **URL**: https://github.com/MeteorLite/hotlite-client
- **Approach**: Swaps/adds JARs on the classpath in the RuneLite launcher before the client runs
- **How it works**: Intercepts class loading in the official Jagex/RuneLite launcher to inject custom code
- **Key detail**: Uses RuneLite's official injected-client for API consistency
- **Relevance**: Shows how to load custom client builds through the Jagex Launcher — potential auth solution
- **Status**: Active (includes Kotlin 1.9.22 support)
- **Compliance note**: "hotlite is compliant out of the box" but the tech doesn't prevent modifications

## 4. Linux on Dex Approach (DISCONTINUED)
- **URL**: Various (Samsung discontinued Linux on Dex in Feb 2020)
- **Approach**: Run full Ubuntu Linux on Samsung tablet, then run RuneLite Linux ARM64
- **Status**: DEAD — Samsung killed the program
- **Successor**: Termux + proot-distro + Termux:X11 (see android-approaches.md)

## 5. Rune-Server OSRS 212 Android Java Port
- **URL**: https://rune-server.org/threads/212-android-java-port.703109/
- **Approach**: Direct Java port of the OSRS client to Android
- **Creator**: Null (original OpenOSRS creator)
- **Features**: Full AWT software rendering, login, world interaction, touch support
- **Status**: Active development, recently updated to latest OSRS revision
- **Key insight**: Same codebase evolved into meteor-mobile

## 6. Alora Mobile Client (Private Server)
- **URL**: Mentioned on rune-server.org
- **Approach**: Mobile client for Alora RSPS with 16 RuneLite plugins
- **Platform**: iOS and Android
- **Status**: Active, with performance updates (web workers, multi-threading)
- **Relevance**: Limited — only works for Alora private server, not official OSRS

---

## Summary of Approaches Found

| Approach | Projects | Feasibility | Status |
|----------|----------|-------------|--------|
| Native Android port (injection) | meteor-mobile, openosrs-mobile | HIGH — proven to work | Semi-maintained |
| Jagex Launcher classpath injection | hotlite-client | HIGH — works with Jagex auth | Active |
| Linux on Dex | N/A | DEAD | Discontinued 2020 |
| Termux + proot + X11 | Community guides | MEDIUM — performance concerns | Community supported |
| Winlator (Windows emulation) | N/A | UNKNOWN — needs testing | Active project |
| Remote desktop/streaming | Parsec, Moonlight, etc. | LOW — latency, requires PC | N/A |
