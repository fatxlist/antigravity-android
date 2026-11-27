# env/overlay/lib

## Overview
Runtime libraries overlaid into `$PREFIX/lib`, pairing the prebuilt Bionic `LD_PRELOAD` path-translation hook (`libtermux-exec.so`) with the Python 3 background daemon runtime package under `daemon/`.

## Subdirectories & Boundaries
- `daemon`: [./daemon/README.md](./daemon/README.md) - Python 3 background task scheduler, trigger evaluators, loopback webhook server, and ConnectRPC/shell worker executor backing `agy-daemon`.
- Prebuilt shared library asset (no contract README): `libtermux-exec.so` — precompiled Bionic `LD_PRELOAD` library loaded by `etc/profile.d/00-env.sh` to rewrite `/bin/sh` and legacy Termux execution paths to `$PREFIX`.
