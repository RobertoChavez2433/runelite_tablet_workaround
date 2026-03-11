# Feature Documentation

Per-package overview and architecture documentation. Each package has two files:
- **Overview** (`feature-{name}-overview.md`) — what it does, key capabilities, how it works
- **Architecture** (`feature-{name}-architecture.md`) — component design, data flow, design decisions

## Package-to-Agent Mapping

| Package | Primary Agent | Files |
|---------|--------------|-------|
| Termux | `termux-shell-agent` | `**/termux/**/*.kt` |
| Auth | `auth-agent` | `**/auth/**/*.kt` |
| Installer | Main session | `**/installer/**/*.kt` |
| Setup | Main session | `**/setup/**/*.kt` |
| UI | Main session | `**/ui/**/*.kt` |
| Shell | `termux-shell-agent` | `assets/scripts/**/*.sh` |

## File Index

| Package | Overview | Architecture |
|---------|---------|-------------|
| Termux | `feature-termux-overview.md` | `feature-termux-architecture.md` |
| Auth | `feature-auth-overview.md` | `feature-auth-architecture.md` |
| Installer | `feature-installer-overview.md` | `feature-installer-architecture.md` |
| Setup | `feature-setup-overview.md` | `feature-setup-architecture.md` |
| UI | `feature-ui-overview.md` | `feature-ui-architecture.md` |
| Shell | `feature-shell-overview.md` | `feature-shell-architecture.md` |

## Related Resources

- Architecture overview: `docs/architecture.md`
- Constraint files: `architecture-decisions/`
- Rule files: `rules/`
- Defect files: `defects/`
