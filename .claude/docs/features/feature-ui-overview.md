# UI Feature Overview

## Purpose

The UI layer provides the visual interface for the setup process using Jetpack Compose with Material 3.

## Key Capabilities

- **Single-screen setup display** showing all 7 steps with status
- **State-driven rendering** via StateFlow collection
- **Material 3 theming** for consistent look and feel
- **Step-level detail** with progress indicators and error messages

## Architecture

- Single Activity (MainActivity) with Compose content
- No Jetpack Navigation — state-driven content switching
- ViewModel exposes `StateFlow<SetupState>` collected via `collectAsState()`
- Each step rendered as a `StepItem` composable

## Key Files

| File | Role |
|------|------|
| `SetupScreen.kt` | Main Compose screen showing setup progress |
| `StepItem.kt` | Individual step row component |
| `Theme.kt` | Material 3 theme configuration |

## Related

- Constraints: `architecture-decisions/ui-constraints.md`
- Rules: `rules/compose-ui.md`
- Defects: `defects/_defects-ui.md`
