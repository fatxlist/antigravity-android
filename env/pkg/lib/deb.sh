#!/data/data/com.agy/files/usr/bin/bash
# deb.sh -- read .deb (ar archive) + validated single-pass inspection & extraction. No `ar` dependency.
# Uses dd/tail/head for ar, tar for payloads, and xz/gzip/zstd/bzip2 by suffix.

# ar_index FILE -> lines of "name\toffset\tsize"
ar_index() {
    local file="$1" off=8 hdr name size data
    while :; do
        hdr="$(dd if="$file" bs=1 skip="$off" count=60 2>/dev/null)"
        [ "${#hdr}" -ge 58 ] || break
        name="$(printf '%s' "$hdr" | cut -c1-16 | sed 's/[[:space:]]*$//; s:/$::')"
        size="$(printf '%s' "$hdr" | cut -c49-58 | sed 's/ //g')"
        [ -n "$name" ] && [ -n "$size" ] || break
        data=$((off + 60))
        printf '%s\t%s\t%s\n' "$name" "$data" "$size"
        off=$((data + size + (size % 2)))
    done
}

# ar_slice FILE OFFSET SIZE -> raw member bytes on stdout
ar_slice() {
    tail -c +$(($2 + 1)) "$1" | head -c "$3"
}

# ar_members FILE -> member names
ar_members() {
    if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" deb-members "$1"
        return $?
    fi
    ar_index "$1" | awk -F'\t' '{ print $1 }'
}

# ar_member FILE NAME -> raw member bytes on stdout
ar_member() {
    local file="$1" want="$2" line off size
    line="$(ar_index "$file" | awk -F'\t' -v w="$want" '$1 == w { print $2 "\t" $3; exit }')"
    [ -n "$line" ] || return 1
    off="${line%%	*}"
    size="${line#*	}"
    ar_slice "$file" "$off" "$size"
}

deb_decode() { # NAME  (reads stdin)
    case "$1" in
        *.xz)  xz -dc ;;
        *.gz)  gzip -dc ;;
        *.zst) zstd -dc ;;
        *.bz2) bzip2 -dc ;;
        *)     cat ;;
    esac
}

_deb_find_member() { # FILE control|data -> "name\toffset\tsize"
    ar_index "$1" | awk -F'\t' -v k="$2" '$1 ~ ("^" k "\\.tar\\.") { print $0; exit }'
}

deb_member_name() { # FILE control|data
    local entry; entry="$(_deb_find_member "$1" "$2")"
    [ -n "$entry" ] || return 1
    printf '%s\n' "${entry%%	*}"
}

deb_field() { # FILE FIELD -> value (authoritative control, single ar/decompress pass)
    if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" deb-field "$1" "$2"
        return $?
    fi
    local f="$1" field="$2" entry m rest off sz val
    entry="$(_deb_find_member "$f" control)"
    [ -n "$entry" ] || return 1
    m="${entry%%	*}"
    rest="${entry#*	}"
    off="${rest%%	*}"
    sz="${rest#*	}"
    val="$(ar_slice "$f" "$off" "$sz" | deb_decode "$m" \
        | tar -xOf - ./control control 2>/dev/null \
        | awk -v k="$field" 'index($0, k":") == 1 { sub(/^[^:]*: /, ""); print; exit }')"
    [ -n "$val" ] || return 1
    printf '%s\n' "$val"
}

# _deb_validate_and_list STRIP [LIST_OUT]
# Reads tar -tf - output on stdin; rejects traversal/absolute/non-prefix entries,
# and optionally writes unique sorted stripped relative paths to LIST_OUT in one pass.
_deb_validate_and_list() {
    local strip="$1" list_out="${2:-}"
    awk -v s="$strip" -v out_file="$list_out" '
        {
            raw = $0
            if (raw == "" || substr(raw, 1, 1) == "/" || index(raw, "\\") > 0) {
                bad = 1; next
            }
            if (raw == "./" || raw == ".") next
            if (substr(raw, 1, 2) != "./") { bad = 1; next }
            rel = substr(raw, 3)
            if (substr(rel, length(rel), 1) == "/") rel = substr(rel, 1, length(rel) - 1)
            n = split(rel, part, "/")
            if (part[1] != "data") { bad = 1; next }
            if (n >= 2 && part[2] != "data") { bad = 1; next }
            if (n >= 3 && part[3] !~ /^[A-Za-z0-9][A-Za-z0-9+._-]*$/) { bad = 1; next }
            if (n >= 4 && part[4] != "files") { bad = 1; next }
            if (n >= 5 && part[5] != "usr") { bad = 1; next }
            for (i = 1; i <= n; i++) if (part[i] == ".." || part[i] == ".") bad = 1

            if (!bad && out_file != "") {
                fn = split(raw, fpart, "/")
                if (fn > 0 && fpart[fn] == "") fn--
                if (fn > s) {
                    p = ""
                    for (j = s + 1; j <= fn; j++) p = p (j > s + 1 ? "/" : "") fpart[j]
                    if (p != "" && !(p in seen)) {
                        seen[p] = 1
                    }
                }
            }
        }
        END {
            if (bad) exit 1
            if (out_file != "") {
                printf "" > out_file
                for (p in seen) print p >> out_file
                close(out_file)
            }
        }
    ' || return 1
    if [ -n "$list_out" ] && [ -s "$list_out" ]; then
        LC_ALL=C sort -u -o "$list_out" "$list_out"
    fi
}

deb_validate_data() { # FILE -- reject absolute/traversal/non-prefix entries
    if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" deb-validate "$1"
        return $?
    fi
    local entry m rest off sz
    entry="$(_deb_find_member "$1" data)"
    [ -n "$entry" ] || return 1
    m="${entry%%	*}"
    rest="${entry#*	}"
    off="${rest%%	*}"
    sz="${rest#*	}"
    ar_slice "$1" "$off" "$sz" | deb_decode "$m" | tar -tf - 2>/dev/null \
        | _deb_validate_and_list "${PKG_DEB_STRIP:-6}"
}

deb_install_data() { # FILE DEST STRIP LIST_OUT -- single ar scan: validate+list then extract
    local f="$1" dest="$2" strip="${3:-${PKG_DEB_STRIP:-6}}" list_out="$4"
    local entry m rest off sz
    entry="$(_deb_find_member "$f" data)"
    [ -n "$entry" ] || return 1
    m="${entry%%	*}"
    rest="${entry#*	}"
    off="${rest%%	*}"
    sz="${rest#*	}"
    ar_slice "$f" "$off" "$sz" | deb_decode "$m" | tar -tf - 2>/dev/null \
        | _deb_validate_and_list "$strip" "$list_out" || return 1
    ar_slice "$f" "$off" "$sz" | deb_decode "$m" \
        | tar -x --strip-components="$strip" -C "$dest" 2>/dev/null
}

deb_extract_data() { # FILE DEST [STRIP]
    local strip="${3:-${PKG_DEB_STRIP:-6}}"
    if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" deb-extract "$1" "$2" --strip "$strip"
        return $?
    fi
    local entry m rest off sz
    entry="$(_deb_find_member "$1" data)"
    [ -n "$entry" ] || return 1
    m="${entry%%	*}"
    rest="${entry#*	}"
    off="${rest%%	*}"
    sz="${rest#*	}"
    ar_slice "$1" "$off" "$sz" | deb_decode "$m" | tar -tf - 2>/dev/null \
        | _deb_validate_and_list "$strip" || return 1
    ar_slice "$1" "$off" "$sz" | deb_decode "$m" | tar -x --strip-components="$strip" -C "$2" 2>/dev/null
}

deb_files() { # FILE -> relative paths under the stripped data prefix
    local strip="${PKG_DEB_STRIP:-6}"
    if command -v python3 >/dev/null 2>&1 && [ -f "${PKG_LIB:-}/pkg_core.py" ]; then
        python3 -B "$PKG_LIB/pkg_core.py" deb-files "$1" --strip "$strip"
        return $?
    fi
    local entry m rest off sz tmp_list
    entry="$(_deb_find_member "$1" data)"
    [ -n "$entry" ] || return 1
    m="${entry%%	*}"
    rest="${entry#*	}"
    off="${rest%%	*}"
    sz="${rest#*	}"
    tmp_list="$(mktemp "${TMPDIR:-/tmp}/deb_files.XXXXXX" 2>/dev/null || mktemp)"
    if ar_slice "$1" "$off" "$sz" | deb_decode "$m" | tar -tf - 2>/dev/null \
            | _deb_validate_and_list "$strip" "$tmp_list"; then
        cat "$tmp_list"
        rm -f "$tmp_list"
        return 0
    fi
    rm -f "$tmp_list"
    return 1
}
