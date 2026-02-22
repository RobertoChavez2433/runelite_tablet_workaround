# Design Section Templates

Standard sections for presenting designs during brainstorming. Scale each section to its complexity — a few sentences if straightforward, up to 200-300 words if nuanced.

## Section Templates

### Overview
- What we're building and why
- Key user benefit
- High-level approach (1-2 sentences)

### Data Model
- Key classes/data structures
- State management approach (StateFlow, sealed class, etc.)
- Relationships between models

### User Flow
- Step-by-step user journey
- Entry points and exit points
- Happy path and key error paths

### UI Components
- Screens and their purpose
- Compose component breakdown
- State hoisting strategy
- Recomposition considerations

### App Architecture
- Package structure
- Dependency flow (who depends on whom)
- Manual DI wiring
- ViewModel <-> Orchestrator <-> Service layers

### External Integration
- Termux IPC (RUN_COMMAND intents, result service)
- PackageInstaller sessions
- Shell script execution via proot
- Network calls (GitHub API, downloads)
- X11/PulseAudio setup

### Edge Cases & Error Handling
- What can go wrong at each step
- Recovery strategies (retry, fallback, user prompt)
- Timeout values and their rationale
- Cleanup on failure (sessions, temp files, processes)

## Android/Kotlin Conventions

When presenting designs, follow these project conventions:

- **DI**: Manual (no Hilt/Koin) — constructor injection, wired in Application/Activity
- **Navigation**: Single-screen (no Jetpack Navigation) — use state to show different content
- **ViewModel**: `ViewModelProvider.Factory` + `by viewModels{}` delegate
- **Async**: Coroutines with structured concurrency, explicit dispatcher assignment
- **UI**: Jetpack Compose with Material 3, state hoisting, `collectAsState()`
- **Lifecycle**: SetupActions callback pattern for Activity-dependent operations
