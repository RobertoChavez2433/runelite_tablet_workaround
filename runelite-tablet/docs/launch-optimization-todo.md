# Launch Optimization TODO

## Goals

- Eliminate visible launch thrash between `RuneLite for Tablet`, `Termux`, and `Termux:X11`.
- Keep `Termux:X11` on a deterministic fresh start for every launch.
- Make session shutdown authoritative so stale children do not poison new launches.
- Raise steady-state performance by reducing avoidable lifecycle churn around the render surface.

## Completed

- Use hidden Termux bootstrap for RuneLite launch instead of opening a visible Termux terminal session.
- Keep duplicate-launch guards in both Kotlin and shell layers.
- Require battery-optimization exemptions for both `com.termux` and `com.termux.x11`.
- Introduce session-owned PID/state files in `launch-runelite.sh`.
- Switch `shutdown-session.sh` to targeted PID shutdown first, with broad process cleanup only as fallback.
- Make the X11 handoff readiness-driven instead of fixed-delay.

## Android Endpoints In Use

1. `com.termux.RUN_COMMAND`
   - Used by `TermuxCommandRunner.execute()` and `launchBackground()`.
   - This is the execution bridge into Termux for setup, launch, health checks, and shutdown.

2. `com.termux.x11.CHANGE_PREFERENCE`
   - Broadcast from `SetupViewModel.launch()` before startup.
   - Used to set fullscreen and resolution mode for `Termux:X11`.

3. `getLaunchIntentForPackage("com.termux.x11")`
   - Used for the visible handoff into `Termux:X11`.
   - Also used by the foreground service for the explicit `Switch to Game` action.

4. Android settings intents
   - `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`
   - `ACTION_APPLICATION_DETAILS_SETTINGS`
   - `ACTION_MANAGE_UNKNOWN_APP_SOURCES`
   - These affect setup UX, not steady-state rendering throughput.

5. App foreground service
   - `RuneLiteSessionService` owns state and exposes `Switch to Game` / `Stop Game`.
   - This service is orchestration only; it should not be on the hot rendering path.

## Verified Throughput Losses

1. Samsung does not classify `com.termux.x11` as a game app.
   - Live logcat repeatedly shows `SemGameManager.isGamePackage(... ret=false)`.

2. Samsung repeatedly freezes `com.termux.x11`.
   - Live logcat repeatedly shows `FreecessHandler: freeze com.termux.x11`.

3. `Termux:X11` notifications are triggering Samsung edge-lighting churn.
   - Live logcat shows repeated `EdgeLighting... com.termux.x11`.

4. Presentation pacing is unstable even when render bursts are high.
   - Prior log passes showed `BLASTBufferQueue ... NO_BUFFER_AVAILABLE`.
   - That points to compositor/buffer pressure, not a strict 30 FPS cap.

5. Our own code still had a little avoidable overhead.
   - The proot-side perf monitor ran every 5 seconds during normal play.
   - The app health monitor woke Termux every 15 seconds even after the session was already stable.

## Changes In This Pass

1. Cut steady-state Termux wakeups.
   - Health polling is now fast during startup, then backs off to 60 seconds once the session is running.

2. Stop unnecessary notification churn.
   - The app foreground-service notification is no longer rebuilt when the content text has not changed.

3. Keep perf monitoring opt-in.
   - The proot-side perf loop is now disabled for normal launches and can be re-enabled explicitly for diagnosis.

## Next

1. Verify steady-state impact on-device.
   - Confirm exactly one launcher, one VirGL server, one X11 loader, and one RuneLite JVM.
   - Re-check logcat for fewer app-side wakes and less notification churn.

2. Add richer health checks.
   - Extend `SessionHealthMonitor` beyond `.rlt-session-alive`.
   - Check session state file, X11 loader PID, VirGL PID, and RuneLite PID.
   - Surface stale-session recovery in the app instead of silent flapping.

3. Remove remaining redundant launch writes.
   - Decide whether display preferences are owned by Kotlin or shell.
   - Keep one authoritative `Termux:X11` preference path and delete the backup path if it is no longer needed.

4. Reduce hot-path startup cost further.
   - Stop regenerating the same Openbox config on every launch.
   - Move static config deployment to setup time.
   - Keep launch-time work focused on process start only.

5. Improve shutdown UX.
   - Add `Force Close Everything` in the app for stale sessions.
   - Show explicit state: `Starting`, `Running`, `Stopping`, `Stale`.
   - If `Stop Game` fails, offer deterministic recovery instead of requiring manual Termux cleanup.

6. Fix the Android/Samsung blockers directly.
   - Add app guidance for disabling `Termux:X11` notifications or edge-lighting on Samsung.
   - Verify both `com.termux` and `com.termux.x11` are exempt after setup.
   - Investigate whether Samsung game classification can be influenced at all for `com.termux.x11`; if not, treat it as an external platform limit.

7. Consider a higher-refresh validation harness.
   - Add a lightweight X11/VirGL frame pacing test outside RuneLite.
   - Verify 120 Hz presentation before blaming client rendering.
   - Use it to separate compositor limits from RuneLite workload limits.
