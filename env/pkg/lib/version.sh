#!/data/data/com.agy/files/usr/bin/bash
# version.sh -- Debian version comparison wrappers.
# Requires PKG_LIB (dir of vercmp.awk).

deb_vercmp() {
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" vercmp "$1" "$2"
        return $?
    fi
    awk -v a="$1" -v b="$2" -f "$PKG_LIB/vercmp.awk"
}
deb_ge() { [ "$(deb_vercmp "$1" "$2")" != "-1" ]; }
deb_gt() { [ "$(deb_vercmp "$1" "$2")" = "1" ]; }
