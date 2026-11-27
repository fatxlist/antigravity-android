#!/data/data/com.agy/files/usr/bin/bash
# fetch.sh -- download with sha256 verification and cache.
# Requires curl, sha256sum, log, die.

# human_size BYTES
human_size() {
    local bytes="$1"
    if [ -z "$bytes" ] || [ "$bytes" -le 0 ] 2>/dev/null; then
        echo ""
    elif [ "$bytes" -ge 1048576 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%.1f MB", b / 1048576 }'
    elif [ "$bytes" -ge 1024 ]; then
        awk -v b="$bytes" 'BEGIN { printf "%d kB", (b + 1023) / 1024 }'
    else
        printf '%d B' "$bytes"
    fi
}

# fetch URL SHA256 OUT [SIZE] [GET_INDEX]
fetch() {
    local url="$1" sha="$2" out="$3" size="${4:-}" idx="${5:-1}" have sz_str=""
    if [ -f "$out" ] && [ -n "$sha" ]; then
        have="$(sha256sum "$out" | cut -d' ' -f1)"
        if [ "$have" = "$sha" ]; then
            return 0
        fi
    fi
    mkdir -p "$(dirname "$out")"
    if [ -n "$size" ]; then
        sz_str=" [$(human_size "$size")]"
    fi
    printf 'Get:%s %s%s\n' "$idx" "$url" "$sz_str"
    local cacert_args=()
    [ -s "${PKG_CERT:-}" ] && cacert_args=(--cacert "$PKG_CERT")
    curl -# -fL --retry 3 --connect-timeout 20 "${cacert_args[@]}" -o "$out.part" "$url" \
        || { rm -f "$out.part"; die "download failed: $url"; }
    if [ -n "$sha" ]; then
        have="$(sha256sum "$out.part" | cut -d' ' -f1)"
        [ "$have" = "$sha" ] || { rm -f "$out.part"; die "sha256 mismatch $(basename "$out"): got $have want $sha"; }
    fi
    mv "$out.part" "$out"
}

_curl_supports_parallel() {
    curl --help all 2>&1 | grep -q -- '--parallel'
}

_curl_supports_http2() {
    curl -V 2>&1 | grep -qi 'HTTP2'
}

_escape_curl_cfg() {
    printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

# fetch_batch -- reads lines of: name|rel_fn|url|sha256|out_path|size
# Downloads missing files concurrently via curl -Z (--http2) and retries only
# failed or hash-mismatched files against remaining PKG_REPOS mirrors.
fetch_batch() {
    local items=() line
    while IFS= read -r line; do
        [ -n "$line" ] && items+=("$line")
    done
    [ "${#items[@]}" -eq 0 ] && return 0

    mkdir -p "$PKG_CACHE" "$PKG_ROOT/tmp"
    local use_parallel=0
    _curl_supports_parallel && use_parallel=1

    local pending=("${items[@]}")
    local mirror_idx=0 total_idx=0
    for line in "${pending[@]}"; do
        total_idx=$((total_idx + 1))
        local name rel_fn url sha out sz sz_str=""
        IFS='|' read -r name rel_fn url sha out sz <<< "$line"
        [ -n "$sz" ] && [ "$sz" -gt 0 ] 2>/dev/null && sz_str=" [$(human_size "$sz")]"
        printf 'Get:%s %s%s\n' "$total_idx" "$url" "$sz_str"
    done

    local cfg="$PKG_ROOT/tmp/curl_batch.$$.cfg"
    trap 'rm -f "$cfg"' RETURN

    while [ "${#pending[@]}" -gt 0 ]; do
        local rewrite_mirror=""
        if [ "$mirror_idx" -gt 0 ]; then
            [ "$mirror_idx" -ge "${#PKG_REPOS[@]}" ] && break
            rewrite_mirror="${PKG_REPOS[$mirror_idx]%/}"
        fi

        if [ "$use_parallel" -eq 1 ]; then
            : > "$cfg"
            if [ -s "${PKG_CERT:-}" ]; then
                printf 'cacert = "%s"\n' "$(_escape_curl_cfg "$PKG_CERT")" >> "$cfg"
            fi
            for line in "${pending[@]}"; do
                local name rel_fn url sha out sz
                IFS='|' read -r name rel_fn url sha out sz <<< "$line"
                [ -n "$rewrite_mirror" ] && [ -n "$rel_fn" ] && url="$rewrite_mirror/${rel_fn#/}"
                mkdir -p "$(dirname "$out")"
                rm -f "$out.part"
                {
                    printf 'url = "%s"\n' "$(_escape_curl_cfg "$url")"
                    printf 'output = "%s"\n' "$(_escape_curl_cfg "$out.part")"
                    printf 'fail\n'
                    printf 'location\n'
                    printf 'retry = 2\n'
                    printf 'connect-timeout = 20\n'
                    printf 'max-time = 300\n'
                } >> "$cfg"
            done
            local curl_flags=(-Z --parallel-max 6 -sS)
            _curl_supports_http2 && curl_flags+=(--http2)
            curl "${curl_flags[@]}" -K "$cfg" || true
        else
            for line in "${pending[@]}"; do
                local name rel_fn url sha out sz
                IFS='|' read -r name rel_fn url sha out sz <<< "$line"
                [ -n "$rewrite_mirror" ] && [ -n "$rel_fn" ] && url="$rewrite_mirror/${rel_fn#/}"
                mkdir -p "$(dirname "$out")"
                local cacert_args=()
                [ -s "${PKG_CERT:-}" ] && cacert_args=(--cacert "$PKG_CERT")
                curl -sSfL --retry 2 --connect-timeout 20 "${cacert_args[@]}" -o "$out.part" "$url" || rm -f "$out.part"
            done
        fi

        local next_pending=()
        for line in "${pending[@]}"; do
            local name rel_fn url sha out sz have
            IFS='|' read -r name rel_fn url sha out sz <<< "$line"
            if [ -f "$out.part" ] && [ -s "$out.part" ]; then
                if [ -n "$sha" ]; then
                    have="$(sha256sum "$out.part" | cut -d' ' -f1)"
                    if [ "$have" = "$sha" ]; then
                        mv "$out.part" "$out"
                        continue
                    fi
                    warn "sha256 mismatch $(basename "$out"): got $have want $sha"
                    rm -f "$out.part"
                else
                    mv "$out.part" "$out"
                    continue
                fi
            else
                rm -f "$out.part"
            fi
            next_pending+=("$line")
        done
        pending=("${next_pending[@]}")
        mirror_idx=$((mirror_idx + 1))
    done

    if [ "${#pending[@]}" -gt 0 ]; then
        local first_name
        IFS='|' read -r first_name _ <<< "${pending[0]}"
        die "download failed after trying mirrors (${#pending[@]} remaining, first: $first_name)"
    fi
}
