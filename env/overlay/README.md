# env/overlay

## Overview
Files overlaid directly onto `$PREFIX` when staging and installing the Antigravity Android userspace environment. Groups executable CLI tools and stdio MCP servers (`bin/`), shell/network/TLS/skill configuration (`etc/`), and Bionic/Python runtime libraries (`lib/`).

## Subdirectories & Boundaries
- `bin`: [./bin/README.md](./bin/README.md) - Executable CLI tools and stdio MCP servers (`agy-mcp-android`, `agy-mcp-browser`, `agy-daemon`, `agy-notify`, `agy-upgrade`, `agy-fix-paths`, `rish`, `cs`) overlaid into `$PREFIX/bin`.
- `etc`: [./etc/README.md](./etc/README.md) - Login profile startup scripts (`profile`, `profile.d/`), DNS/hosts/MOTD/TLS/repo configuration, and bundled Antigravity agent rules and skills overlaid into `$PREFIX/etc`.
- `lib`: [./lib/README.md](./lib/README.md) - Prebuilt `libtermux-exec.so` `LD_PRELOAD` library and the `daemon/` Python package backing `agy-daemon`, overlaid into `$PREFIX/lib`.
