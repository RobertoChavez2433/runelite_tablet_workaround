# Display Size & Launch UX Design

**Date**: 2026-02-22
**Status**: APPROVED
**Fixes**: Defect [UX] tiny window + Defect [UX] confusing app-switching

## Problem Statement

### Display Size (#2)
RuneLite renders in a 1038x503 window on a 2960x1711 X11 desktop. The OSRS client defaults to 765x503 (original fixed resolution) + sidebar. No window manager is running, so nothing maximizes or resizes the window.

**Root cause**: Bare X11 (no window manager) = windows open at their default/preferred size.

### Launch UX (#3)
User taps "Launch RuneLite" → Termux terminal appears → user must manually find and switch to Termux:X11 app to see the RuneLite display. Additionally, Termux:X11 shows a virtual keyboard bar and Android status/nav bars, wasting screen space.

**Root cause**: No auto-switch to Termux:X11, no fullscreen configuration.

## Investigation Findings

- **X11 desktop resolution**: 2960x1711 (correct — matches tablet screen minus Android system bars)
- **RuneLite window**: 1038x503 at position +1082,+604 (centered, not maximized)
- **Termux:X11 preferences**: Controllable via `CHANGE_PREFERENCE` broadcast intent or `termux-x11-preference` CLI
- **Key preferences**: `fullscreen`, `showAdditionalKbd`, `displayResolutionMode`
- **Termux:X11 activity**: Launchable via explicit intent `com.termux.x11/.MainActivity`

## Design

### Approach: Openbox WM + Termux:X11 Preference Control + Auto-Switch

Three coordinated changes:
1. **Openbox window manager** inside proot — auto-maximizes RuneLite
2. **Termux:X11 preferences** set from Kotlin — fullscreen, no virtual keyboard
3. **Auto-switch** to Termux:X11 from shell script after X11 socket ready

### Launch Sequence (end to end)

```
User taps "Launch RuneLite"
  → Kotlin: sendBroadcast(CHANGE_PREFERENCE) to Termux:X11
      fullscreen=true, showAdditionalKbd=false, displayResolutionMode=native
  → Kotlin: startService(RUN_COMMAND) → launch-runelite.sh
      → Shell: start termux-x11 :0
      → Shell: poll for X11 socket (up to 10s)
      → Shell: am start Termux:X11 activity (user sees fullscreen X11)
      → Shell: termux-x11-preference (backup config, in case broadcast missed)
      → Shell: proot-distro login ubuntu
          → openbox --config-file rc.xml &  (auto-maximize, no decorations)
          → exec java -cp ... RuneLite
              → openbox maximizes RuneLite window to full X11 resolution
              → RuneLite fills the entire Termux:X11 display
```

### What the User Sees

1. Tap "Launch RuneLite"
2. Brief moment (~2-3s) while X11 starts
3. Termux:X11 appears fullscreen (no status bar, no nav bar, no keyboard bar)
4. RuneLite window fills the entire screen
5. Game is ready to play

## File Changes

### Modified Files

#### `launch-runelite.sh`
- After X11 socket detected: run `am start --user 0 -n com.termux.x11/com.termux.x11.MainActivity`
- After X11 socket detected: run `termux-x11-preference` as backup config
- Inside proot: start `openbox --config-file /root/.config/openbox/rc.xml &` before RuneLite
- Add `sleep 0.5` after openbox to let it initialize

#### `setup-environment.sh`
- Add `openbox` to `apt-get install` list inside proot Ubuntu
- Deploy openbox config: copy `openbox-rc.xml` to `/root/.config/openbox/rc.xml`

#### `SetupViewModel.kt`
- In `launch()`: Before sending RUN_COMMAND, send `CHANGE_PREFERENCE` broadcast:
  ```kotlin
  val prefIntent = Intent("com.termux.x11.CHANGE_PREFERENCE").apply {
      setPackage("com.termux.x11")
      putExtra("fullscreen", "true")
      putExtra("showAdditionalKbd", "false")
      putExtra("displayResolutionMode", "native")
  }
  context.sendBroadcast(prefIntent)
  ```

#### `AndroidManifest.xml`
- Add `<queries><package android:name="com.termux.x11" /></queries>` for Android 11+ package visibility

#### `ScriptManager.kt`
- Extend to deploy config files from `assets/configs/` alongside scripts

### New Files

#### `assets/configs/openbox-rc.xml`
Openbox configuration:
- `<maximized>yes</maximized>` — maximize all new windows
- `<decor>no</decor>` — no title bar or window borders
- No desktop menus or keyboard shortcuts
- Minimal config (~20-30 lines)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<openbox_config xmlns="http://openbox.org/3.4/rc">
  <resistance>
    <strength>10</strength>
    <screen_edge_strength>20</screen_edge_strength>
  </resistance>
  <focus>
    <followMouse>no</followMouse>
  </focus>
  <placement>
    <policy>Smart</policy>
  </placement>
  <applications>
    <application class="*">
      <maximized>yes</maximized>
      <decor>no</decor>
    </application>
  </applications>
</openbox_config>
```

## New Dependencies

| Package | Environment | Size | Purpose |
|---------|-------------|------|---------|
| `openbox` | proot Ubuntu | ~10-15MB | Window manager — auto-maximize RuneLite |

## Performance Impact

- **Openbox**: Near-zero CPU/memory at runtime. Only activates on window events. ~3-5MB resident memory.
- **Fullscreen mode**: X11 resolution remains native (2960x1711). More pixels for RuneLite to render, but this is a renderer bottleneck (llvmpipe), not a WM issue.
- **Note**: RuneLite's "Stretched Mode" plugin can render at lower internal resolution and scale up for better FPS. This is an independent optimization for later.

## Termux:X11 Preference Reference

| Key | Type | Value | Purpose |
|-----|------|-------|---------|
| `fullscreen` | string | `"true"` | Hide Android status bar and navigation bar |
| `showAdditionalKbd` | string | `"false"` | Hide virtual keyboard bar (ESC, CTRL, etc.) |
| `displayResolutionMode` | string | `"native"` | Use tablet's native resolution |

**Broadcast action**: `com.termux.x11.CHANGE_PREFERENCE`
**Activity**: `com.termux.x11/com.termux.x11.MainActivity`
**Stop action**: `com.termux.x11.ACTION_STOP`

## Edge Cases

| Scenario | Handling |
|----------|----------|
| Termux:X11 not installed | Setup step 2 installs it. Launch won't reach this code without it. |
| Preference broadcast fails silently | Shell script also calls `termux-x11-preference` as backup |
| Openbox not installed (setup interrupted) | RuneLite still launches, just not maximized. Graceful degradation. |
| Physical keyboard disconnected mid-game | Android soft keyboard auto-appears for text input fields. Termux:X11 handles this. |
| First launch flash (non-fullscreen) | Preferences persist after first set. Subsequent launches start fullscreen immediately. |

## Testing Plan

1. Build APK with all changes
2. Run `setup-environment.sh` to install openbox
3. Tap "Launch RuneLite"
4. Verify: Termux:X11 appears fullscreen (no bars)
5. Verify: RuneLite window fills entire screen
6. Verify: Can interact with RuneLite (click, type with physical keyboard)
7. Verify: Second launch also starts fullscreen immediately
8. Take ADB screenshots before/after for comparison
