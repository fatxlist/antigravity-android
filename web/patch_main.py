#!/usr/bin/env python3
"""
web/patch_main.py - Dynamic, resilient patcher for web/generated/main.js.

Applies independent, idempotent mobile patches:
1. Appearance -> Desktop Mode toggle.
2. Settings -> Accounts & Saved Accounts switcher (`window.__agAccountSwitcher`).
3. Relaxed chat composer text/code file attachments (`10MB` limit + extra extensions).

If minified signatures shift in a future upstream release, unmatched hooks warn
and leave the rest untouched so builds never break (`mobile_bridge.js` provides
fallback Quick Controls).
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile

IDENT_RE = r"[A-Za-z0-9_$]+"


def _identifier_declared(content, ident):
    """Verify a minified component identifier is declared in the bundle."""
    if not ident or not re.fullmatch(IDENT_RE, ident):
        return False
    pattern = re.compile(
        r"(?:^|[;,{\s])(?:var|let|const|function|class)\s+" + re.escape(ident) + r"(?=\s*[=(;,{])"
    )
    return pattern.search(content) is not None


def _detect_setting_components(content, react_id):
    """Dynamically locate the setting row and switch components around rightElement:."""
    escaped_react = re.escape(react_id)
    combo_re = re.compile(
        escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{label:[^}]*rightElement:"
        + escaped_react
        + r"\.createElement\(("
        + IDENT_RE
        + r"),\{checked:"
    )

    for match in re.finditer(r"rightElement:", content):
        idx = match.start()
        window = content[max(0, idx - 240) : min(len(content), idx + 180)]
        found = combo_re.search(window)
        if found:
            item_comp = found.group(1)
            switch_comp = found.group(2)
            if _identifier_declared(content, item_comp) and _identifier_declared(content, switch_comp):
                return item_comp, switch_comp
    return None, None


def _verify_js_syntax(js_path):
    """Optionally verify patched JS syntax using `node --check` when node is available."""
    node_bin = shutil.which("node")
    if not node_bin:
        return True, "node not found; skipped syntax verification"
    try:
        proc = subprocess.run(
            [node_bin, "--check", js_path],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=30,
        )
    except Exception as exc:
        return False, f"node --check invocation error: {exc}"
    if proc.returncode != 0:
        err = (proc.stderr or proc.stdout or "").strip()
        return False, f"node --check failed: {err}"
    return True, "node --check ok"


def _inject_desktop_mode(content):
    """Inject Desktop Mode toggle into Appearance settings, returning (content, changed)."""
    if "Desktop Mode" in content or "ag_desktop_mode" in content:
        print("[web-patch] Desktop Mode toggle already present in main.js")
        return content, False

    theme_idx = content.find('{label:"Theme"')
    theme_match = None
    react_id = None
    if theme_idx != -1:
        window_start = max(0, theme_idx - 100)
        window_end = min(len(content), theme_idx + 500)
        window_text = content[window_start:window_end]
        theme_pattern = re.compile(
            r"(" + IDENT_RE + r")\.createElement\((" + IDENT_RE + r'),\{label:"Theme",.*?onSelect:' + IDENT_RE + r"\}\)"
        )
        theme_match = theme_pattern.search(window_text)
        if theme_match:
            react_id = theme_match.group(1)

    hook_anchor = None
    if react_id and theme_idx != -1:
        app_window_start = max(0, theme_idx - 400)
        app_window = content[app_window_start:theme_idx]
        ret_pattern = re.compile(
            r"(;return\s+" + re.escape(react_id) + r"\.createElement\([^;]{0,160}\{title:\"Appearance\"\})"
        )
        ret_match = ret_pattern.search(app_window)
        if ret_match:
            hook_anchor = ret_match.group(1)

    if not hook_anchor:
        hook_pattern = re.compile(
            r"(var\s+"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==3,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"!==2,\s*"
            + IDENT_RE
            + r"="
            + IDENT_RE
            + r"=>\s*"
            + IDENT_RE
            + r"=>\{.*?\};)"
        )
        hook_match = hook_pattern.search(content)
        if hook_match:
            hook_anchor = hook_match.group(1)

    item_comp, switch_comp = (
        _detect_setting_components(content, react_id) if react_id else (None, None)
    )

    if not hook_anchor or not theme_match or not react_id or not item_comp or not switch_comp:
        print(
            "[web-patch] WARNING: upstream main.js signatures not matched for Desktop Mode "
            f"(hook={bool(hook_anchor)}, theme={bool(theme_match)}, "
            f"item={item_comp}, switch={switch_comp}); skipping Desktop Mode patch.",
            file=sys.stderr,
        )
        return content, False

    hook_code = (
        f"var [isDesktop,setIsDesktop]=(0,{react_id}.useState)(()=>{{"
        "try{if(window.Android&&typeof window.Android.isDesktopMode==='function')return window.Android.isDesktopMode();"
        "var s=localStorage.getItem('ag_desktop_mode');return s===null?!1:s==='true'}catch(_){return !1}});"
        f"var onDesktopToggle=(0,{react_id}.useCallback)(v=>{{"
        "setIsDesktop(v);try{localStorage.setItem('ag_desktop_mode',String(v));"
        "if(window.Android&&typeof window.Android.setDesktopMode==='function')window.Android.setDesktopMode(v)}catch(_){}},[]);"
    )
    if hook_anchor.startswith(";return"):
        injected_hook = ";" + hook_code + hook_anchor[1:]
    else:
        injected_hook = hook_code + hook_anchor

    orig_theme = theme_match.group(0)
    injected_theme = (
        orig_theme
        + f',{react_id}.createElement({item_comp},{{label:"Desktop Mode",description:"Use desktop viewport and user agent for the workspace",'
        + f"rightElement:{react_id}.createElement({switch_comp},{{checked:isDesktop,onCheckedChange:onDesktopToggle}})}})"
    )

    patched = content.replace(hook_anchor, injected_hook, 1)
    patched = patched.replace(orig_theme, injected_theme, 1)
    print(f"[web-patch] Patched Desktop Mode toggle (react={react_id}, item={item_comp}, switch={switch_comp})")
    return patched, True


def _inject_account_switcher(content):
    """Inject Persistent Account Vault & Switcher into Settings -> Accounts and Account."""
    if "__agAccountSwitcher" in content or "AgAccountsScreen" in content:
        print("[web-patch] Account Switcher already present in main.js")
        return content, False

    gob_idx = content.find("Manage your plan, credentials, and general preferences.")
    if gob_idx == -1:
        print("[web-patch] WARNING: account switcher anchor not found; skipping.", file=sys.stderr)
        return content, False

    gob_win_start = max(0, gob_idx - 2500)
    gob_win = content[gob_win_start : min(len(content), gob_idx + 4500)]
    gob_m = re.search(
        rf"var\s+({IDENT_RE})=\(\{{isActive:{IDENT_RE}\}}=\{{\}}\)=>\{{var\s+({IDENT_RE})=({IDENT_RE})\(\)\.core\.authService,.*?"
        rf"({IDENT_RE})=({IDENT_RE})\(({IDENT_RE})\),({IDENT_RE})=\5\(({IDENT_RE})\),.*?"
        rf"({IDENT_RE})=({IDENT_RE})\(\),.*?"
        rf"({IDENT_RE})=({IDENT_RE})\(\),.*?"
        rf"({IDENT_RE})=\9\.get\(\"isRemoteControl\"\)\?\?!1,({IDENT_RE})=\11\.useCloseSettings\(\)",
        gob_win,
        re.DOTALL,
    )
    if not gob_m:
        print("[web-patch] WARNING: account switcher header not matched; skipping.", file=sys.stderr)
        return content, False

    auth_svc_var = gob_m.group(2)
    sd_fn = gob_m.group(3)
    user_status_var = gob_m.group(4)
    el_fn = gob_m.group(5)
    sb_sel = gob_m.group(6)
    xk_sel = gob_m.group(8)
    nj_fn = gob_m.group(10)
    ol_fn = gob_m.group(12)

    lu_m = re.search(
        rf"({IDENT_RE})\.createElement\(({IDENT_RE}),\{{title:\"Account\",description:\"Manage your plan, credentials, and general preferences\.\"\}},.*?"
        rf"\1\.createElement\(({IDENT_RE}),\{{title:\"Account\"\}},.*?"
        rf"\1\.createElement\(({IDENT_RE}),null,{IDENT_RE}&&\1\.createElement\(({IDENT_RE}),",
        gob_win,
        re.DOTALL,
    )
    if not lu_m:
        print("[web-patch] WARNING: account switcher Settings containers not matched; skipping.", file=sys.stderr)
        return content, False
    react_id, lu_comp, pu_comp, nu_comp, ou_comp = (
        lu_m.group(1), lu_m.group(2), lu_m.group(3), lu_m.group(4), lu_m.group(5)
    )

    bi_m = re.search(
        rf'{re.escape(react_id)}\.createElement\(({IDENT_RE}),\{{variant:"secondary",size:"sm","data-testid":"g1-referral-invite-button"',
        gob_win,
        re.DOTALL,
    )
    nav_idx = content.find('"settings-nav-item-Account"')
    dgb_m = (
        re.search(rf"{re.escape(react_id)}\.createElement\(({IDENT_RE}),\{{src:", content[nav_idx : min(len(content), nav_idx + 400)])
        if nav_idx != -1 else None
    )
    ew_idx = content.find('name:"arrow_back"')
    ew_m = (
        re.search(rf'{re.escape(react_id)}\.createElement\(({IDENT_RE}),\{{name:"arrow_back"', content[max(0, ew_idx - 80) : ew_idx + 40])
        if ew_idx != -1 else None
    )
    ee_idx = content.find('"confirm-delete-all-user-data"')
    ee_m = (
        re.search(rf"{re.escape(react_id)}\.createElement\(({IDENT_RE}),\{{size:16\}}", content[ee_idx : min(len(content), ee_idx + 300)])
        if ee_idx != -1 else None
    )
    kb_idx = content.find("[UserStatusSync] Failed to fetch user status:")
    kb_m = (
        re.search(
            rf"function\s+({IDENT_RE})\(\)\{{var\s+{IDENT_RE}=({IDENT_RE})\(\),{IDENT_RE}=\(0,{re.escape(react_id)}\.useContext\)",
            content[kb_idx : min(len(content), kb_idx + 1200)],
            re.DOTALL,
        )
        if kb_idx != -1 else None
    )
    ib_m = (
        re.search(
            rf"(function\s+{IDENT_RE}\(\{{agentService:{IDENT_RE},store:({IDENT_RE}),.*?"
            rf"\{{userStatus:({IDENT_RE})\}}=[^;]+;if\(!{IDENT_RE}\(\)\)\{{if\(\3\)\{{)",
            content[max(0, kb_idx - 900) : kb_idx],
            re.DOTALL,
        )
        if kb_idx != -1 else None
    )
    wmb_idx = content.find('[{screen:"General"},')
    xmb_m = (
        re.search(
            rf"(function\s+{IDENT_RE}\(({IDENT_RE}),({IDENT_RE})\)\{{return\s+{IDENT_RE}\.map\(({IDENT_RE})=>\4\.screen\)\.filter\(\4=>)(\(!{IDENT_RE}\.includes\(\4\)\|\|\3\.includes\(\4\)\)&&\2\(\4\)\)\}})",
            content[wmb_idx : min(len(content), wmb_idx + 700)],
            re.DOTALL,
        )
        if wmb_idx != -1 else None
    )
    oxb_idx = content.find('case "Account":return')
    oxb_m = (
        re.search(
            rf'(function\s+{IDENT_RE}\({IDENT_RE},{IDENT_RE}=!0\)\{{.*?switch\({IDENT_RE}\)\{{)(case\s+"Account":)',
            content[max(0, oxb_idx - 220) : min(len(content), oxb_idx + 60)],
            re.DOTALL,
        )
        if oxb_idx != -1 else None
    )
    gob_tail_m = re.search(
        rf'("Sign In"\)\}}\)\)\)\)),({IDENT_RE}&&{IDENT_RE}&&{re.escape(react_id)}\.createElement\({IDENT_RE},\{{deleteAllUserData:{IDENT_RE}\}}\))',
        content[gob_idx : min(len(content), gob_idx + 5200)],
        re.DOTALL,
    )

    missing = [
        k for k, v in (
            ("BI", bi_m), ("Dgb", dgb_m), ("Ew", ew_m), ("eE", ee_m),
            ("K_b", kb_m), ("I_b", ib_m), ("xMb", xmb_m), ("oXb", oxb_m), ("gob_tail", gob_tail_m)
        ) if v is None
    ]
    if missing:
        print(f"[web-patch] WARNING: account switcher anchors missing ({', '.join(missing)}); skipping.", file=sys.stderr)
        return content, False

    bi_comp, dgb_comp, ew_comp, ee_comp = bi_m.group(1), dgb_m.group(1), ew_m.group(1), ee_m.group(1)
    kb_fn, cl_fn = kb_m.group(1), kb_m.group(2)
    ib_prefix, ib_store, ib_status = ib_m.group(1), ib_m.group(2), ib_m.group(3)
    xmb_c = xmb_m.group(4)

    patched = content
    ib_hook = (
        f"try{{window.__agAccountSwitcher&&{ib_status}.email&&"
        f"window.__agAccountSwitcher.syncProfile({ib_status},{xk_sel}({ib_store}.getState())?.context)}}catch(_){{}}"
    )
    patched = patched.replace(ib_prefix, ib_prefix + ib_hook, 1)

    # Re-locate GOb header after earlier replacement shifted offsets minimally
    gob_idx2 = patched.find("Manage your plan, credentials, and general preferences.")
    gob_win2_start = max(0, gob_idx2 - 2500)
    gob_win2 = patched[gob_win2_start : min(len(patched), gob_idx2 + 4500)]
    gob_m2 = re.search(re.escape(gob_m.group(0)), gob_win2)
    if not gob_m2:
        print("[web-patch] WARNING: account switcher GOb re-match failed; skipping.", file=sys.stderr)
        return content, False
    gob_abs_start = gob_win2_start + gob_m2.start()
    gob_header_str = gob_m2.group(0)
    gob_anchor_prefix = f"var {auth_svc_var}={sd_fn}().core.authService"
    gob_hooks = (
        f"var _agStore={cl_fn}(),_agRefreshStatus={kb_fn}(),"
        f"_agAuthState={el_fn}({xk_sel}),_agRc={nj_fn}().get(\"isRemoteControl\")??!1,"
        f"_agCloseSettings={ol_fn}().useCloseSettings();"
    )
    patched = (
        patched[:gob_abs_start]
        + gob_header_str.replace(gob_anchor_prefix, gob_hooks + gob_anchor_prefix, 1)
        + patched[gob_abs_start + len(gob_header_str) :]
    )

    section_call = (
        f"(window.__agAccountSwitcher?window.__agAccountSwitcher.renderSection({{"
        f"React:{react_id},LU:{lu_comp},PU:{pu_comp},NU:{nu_comp},OU:{ou_comp},"
        f"BI:{bi_comp},Dgb:{dgb_comp},Ew:{ew_comp},eE:{ee_comp},"
        f"userStatus:{user_status_var},authState:_agAuthState,authService:{auth_svc_var},"
        f"refreshUserStatus:_agRefreshStatus,getStoreState:()=>_agStore.getState(),"
        f"closeSettings:_agCloseSettings,isRemoteControl:_agRc}}):null)"
    )
    patched = patched.replace(
        gob_tail_m.group(0),
        f"{gob_tail_m.group(1)},{section_call},{gob_tail_m.group(2)}",
        1,
    )

    patched = patched.replace('[{screen:"General"},', '[{screen:"General"},{screen:"Accounts",label:"Accounts"},', 1)
    patched = patched.replace(xmb_m.group(0), f'{xmb_m.group(1)}{xmb_c}==="Accounts"||{xmb_m.group(5)}', 1)

    ag_screen_comp = (
        f"function AgAccountsScreen(){{"
        f"var _u={el_fn}({sb_sel}),_au={el_fn}({xk_sel}),_as={sd_fn}().core.authService,"
        f"_rf={kb_fn}(),_st={cl_fn}(),_cs={ol_fn}().useCloseSettings(),"
        f"_rc={nj_fn}().get(\"isRemoteControl\")??!1;"
        f"return window.__agAccountSwitcher?window.__agAccountSwitcher.renderScreen({{"
        f"React:{react_id},LU:{lu_comp},PU:{pu_comp},NU:{nu_comp},OU:{ou_comp},"
        f"BI:{bi_comp},Dgb:{dgb_comp},Ew:{ew_comp},eE:{ee_comp},"
        f"userStatus:_u,authState:_au,authService:_as,"
        f"refreshUserStatus:_rf,getStoreState:()=>_st.getState(),"
        f"closeSettings:_cs,isRemoteControl:_rc}}):null}}"
    )
    patched = patched.replace(
        oxb_m.group(0),
        f'{ag_screen_comp}{oxb_m.group(1)}case "Accounts":return {react_id}.createElement(AgAccountsScreen,null);{oxb_m.group(2)}',
        1,
    )

    print(f"[web-patch] Patched Account Switcher into Settings (react={react_id})")
    return patched, True


def _inject_any_file_attachments(content):
    """Expand composer accepted text/code extensions, raise text limit to 10MB, and relax MIME guard."""
    if 'Text file size exceeds 10MB limit' in content and 'smali:"text/plain"' in content:
        print("[web-patch] Any-file attachments already present in main.js")
        return content, False

    patched = content
    changed = False

    ext_anchor = 'dart:"text/plain",scala:"text/plain",gradle:"text/plain"}'
    extra_exts = (
        'dart:"text/plain",scala:"text/plain",gradle:"text/plain",'
        'log:"text/plain",ini:"text/plain",cfg:"text/plain",conf:"text/plain",'
        'properties:"text/plain",env:"text/plain",diff:"text/plain",patch:"text/plain",'
        'mk:"text/plain",cmake:"text/plain",groovy:"text/plain",smali:"text/plain",'
        'pro:"text/plain",mdx:"text/plain",rst:"text/plain",tex:"text/plain",'
        'fish:"text/plain",nix:"text/plain",zig:"text/plain",tf:"text/plain",'
        'hcl:"text/plain",sol:"text/plain",bat:"text/plain",cmd:"text/plain",'
        'tsv:"text/csv",lock:"text/plain",plist:"text/plain",svg:"image/svg+xml"}'
    )
    if ext_anchor in patched:
        patched = patched.replace(ext_anchor, extra_exts, 1)
        changed = True

    old_limit = 'p.size>1048576)d?.("Text file size exceeds 1MB limit"),h(Qg.VALIDATION_CHECK_FAILED,Error("Text file size exceeds 1MB limit"))'
    new_limit = 'p.size>10485760)d?.("Text file size exceeds 10MB limit"),h(Qg.VALIDATION_CHECK_FAILED,Error("Text file size exceeds 10MB limit"))'
    if old_limit in patched:
        patched = patched.replace(old_limit, new_limit, 1)
        changed = True

    old_mime_guard = 'if(!g||g.get(q)===!0)return!1;p=BPa(p.name);'
    new_mime_guard = 'if(!g||g.get(q)===!0||q==="text/plain"||q.startsWith("text/")||q==="application/json"||q==="application/octet-stream")return!1;p=BPa(p.name);'
    if old_mime_guard in patched:
        patched = patched.replace(old_mime_guard, new_mime_guard, 1)
        changed = True

    if changed:
        print("[web-patch] Patched composer any-file attachments (extra exts, 10MB text limit, text MIME pass-through)")
    else:
        print("[web-patch] WARNING: composer any-file anchors not matched; skipping.", file=sys.stderr)
    return patched, changed


def patch_main(file_path):
    if not os.path.isfile(file_path):
        print(f"[web-patch] file not found: {file_path}", file=sys.stderr)
        return False

    with open(file_path, "r", encoding="utf-8") as f:
        content = f.read()

    patched = content
    any_changed = False
    for fn in (_inject_desktop_mode, _inject_account_switcher, _inject_any_file_attachments):
        patched, did = fn(patched)
        any_changed = any_changed or did

    if not any_changed:
        return True

    target_dir = os.path.dirname(os.path.abspath(file_path))
    tmp_path = None
    try:
        fd, tmp_path = tempfile.mkstemp(prefix=".patch_main_", suffix=".js", dir=target_dir)
        with os.fdopen(fd, "w", encoding="utf-8") as tmp_f:
            tmp_f.write(patched)

        ok, msg = _verify_js_syntax(tmp_path)
        if not ok:
            print(
                f"[web-patch] WARNING: patched main.js failed syntax check ({msg}); "
                "keeping unpatched copy (mobile_bridge.js provides resilient fallback controls).",
                file=sys.stderr,
            )
            os.remove(tmp_path)
            tmp_path = None
            return True

        os.replace(tmp_path, file_path)
        tmp_path = None
    finally:
        if tmp_path and os.path.exists(tmp_path):
            try:
                os.remove(tmp_path)
            except OSError:
                pass

    print(f"[web-patch] Saved patched main.js ({msg})")
    return True


if __name__ == "__main__":
    path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), "main.js")
    if not patch_main(path):
        sys.exit(1)
