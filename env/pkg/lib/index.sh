#!/data/data/com.agy/files/usr/bin/bash
# index.sh -- Packages index helpers.
# Requires PKG_LIB, PKG_INDEX, deb_gt (version.sh).

# index_parse FILE -> TSV (see index.awk / pkg_core.py for columns)
index_parse() {
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" index-parse "$1"
        return $?
    fi
    awk -f "$PKG_LIB/index.awk" "$1"
}

# index_rebuild FILE.Packages -> writes FILE.tsv
index_rebuild() {
    [ -f "$1" ] || return 1
    local tsv="${1%.Packages}.tsv"
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" index-parse "$1" > "$tsv.tmp" && mv "$tsv.tmp" "$tsv"
        return $?
    fi
    awk -f "$PKG_LIB/index.awk" "$1" > "$tsv"
}

index_rebuild_all() {
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" index-rebuild "$PKG_INDEX"
        return $?
    fi
    local f
    for f in "$PKG_INDEX"/repo.*.Packages; do
        [ -e "$f" ] || continue
        index_rebuild "$f"
    done
}

index_tsvs() { ls "$PKG_INDEX"/repo.*.tsv 2>/dev/null; }

# index_lookup NAME -> "repo.N<TAB>line" of the highest version (all repos)
index_lookup() {
    local name="$1" tsv base line v best="" bestver=""
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" index-lookup "$PKG_INDEX" "$name"
        return $?
    fi
    for tsv in $(index_tsvs); do
        base="$(basename "$tsv" .tsv)"
        while IFS= read -r line; do
            v="$(printf '%s' "$line" | cut -f2)"
            if [ -z "$bestver" ] || deb_gt "$v" "$bestver"; then
                bestver="$v"; best="$base	$line"
            fi
        done < <(awk -F'\t' -v n="$name" '$1 == n' "$tsv")
    done
    [ -n "$best" ] && printf '%s\n' "$best"
}

# index_provider NAME -> "repo.N<TAB>line" of a package whose Provides has NAME
index_provider() {
    local name="$1" tsv base line
    if command -v python3 >/dev/null 2>&1 && [ -f "$PKG_LIB/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" index-provider "$PKG_INDEX" "$name"
        return $?
    fi
    for tsv in $(index_tsvs); do
        base="$(basename "$tsv" .tsv)"
        while IFS= read -r line; do
            printf '%s\t%s\n' "$base" "$line"; return 0
        done < <(awk -F'\t' -v n="$name" '{
            c = split($7, a, ",")
            for (i = 1; i <= c; i++) { gsub(/ /, "", a[i]); if (a[i] == n) { print; exit } }
        }' "$tsv")
    done
}

# E1 compat
index_get() { index_parse "$1" | awk -F'\t' -v n="$2" -v c="$3" '$1 == n { print $c }' | tail -n 1; }
index_best() { index_parse "$1" | awk -F'\t' -v n="$2" '$1 == n'; }
