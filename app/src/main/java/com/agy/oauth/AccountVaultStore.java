package com.agy.oauth;

import android.content.Context;
import android.system.Os;
import android.util.Log;

import com.agy.util.IoUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Persistent, owner-only (0600) vault of every Antigravity account the user has
 * signed into. Snapshots the standalone OAuth token payload together with
 * display metadata so switching accounts is fast and never exposes raw tokens
 * to WebView JavaScript.
 */
public final class AccountVaultStore {

    private static final String TAG = "AccountVaultStore";
    private static final String VAULT_FILE = "agy-account-vault.json";
    private static final int VAULT_VERSION = 1;
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Object LOCK = new Object();

    private AccountVaultStore() {
    }

    public static File vaultFile(Context context) {
        if (context == null) {
            return null;
        }
        return new File(new File(context.getFilesDir(), OAuthConstants.TOKEN_DIR), VAULT_FILE);
    }

    /** Captures the active token (if valid) + profile metadata and returns public account list JSON. */
    public static String syncCurrentAccount(Context context, String profileJson) {
        return syncCurrentAccount(OAuthTokenStore.tokenFile(context), vaultFile(context), profileJson);
    }

    /** File-based overload for pure JVM testing and isolated token storage. */
    public static String syncCurrentAccount(File tokenFile, File vaultFile, String profileJson) {
        synchronized (LOCK) {
            syncCurrentAccountLocked(tokenFile, vaultFile, profileJson);
            return listAccountsLocked(tokenFile, vaultFile).toString();
        }
    }

    /** Returns sanitized public account descriptors sorted active-first, then most recently used. */
    public static String listAccountsJson(Context context) {
        return listAccountsJson(OAuthTokenStore.tokenFile(context), vaultFile(context));
    }

    public static String listAccountsJson(File tokenFile, File vaultFile) {
        synchronized (LOCK) {
            return listAccountsLocked(tokenFile, vaultFile).toString();
        }
    }

    /** Switches the active standalone OAuth token file to {@code accountId}, preserving outgoing token. */
    public static String activateAccount(Context context, String accountId, String outgoingProfileJson) {
        return activateAccount(OAuthTokenStore.tokenFile(context), vaultFile(context), accountId, outgoingProfileJson);
    }

    public static String activateAccount(File tokenFile, File vaultFile,
                                         String accountId, String outgoingProfileJson) {
        synchronized (LOCK) {
            JSONObject result = new JSONObject();
            try {
                if (tokenFile == null || vaultFile == null) {
                    result.put("ok", false);
                    result.put("error", "missing_storage_path");
                    return result.toString();
                }
                String targetId = accountId != null ? accountId.trim() : "";
                if (targetId.isEmpty()) {
                    result.put("ok", false);
                    result.put("error", "missing_account_id");
                    return result.toString();
                }
                syncCurrentAccountLocked(tokenFile, vaultFile, outgoingProfileJson);
                JSONObject vault = readVaultLocked(vaultFile);
                JSONArray accounts = vault.optJSONArray("accounts");
                JSONObject target = null;
                if (accounts != null) {
                    for (int i = 0; i < accounts.length(); i++) {
                        JSONObject item = accounts.optJSONObject(i);
                        if (item != null && targetId.equals(item.optString("id", ""))) {
                            target = item;
                            break;
                        }
                    }
                }
                if (target == null) {
                    result.put("ok", false);
                    result.put("error", "account_not_found");
                    return result.toString();
                }
                String tokenPayload = target.optString("token_json", "");
                if (!isValidTokenJson(tokenPayload)) {
                    result.put("ok", false);
                    result.put("error", "missing_saved_token");
                    return result.toString();
                }
                if (!writePrivateAtomic(tokenFile, tokenPayload)) {
                    result.put("ok", false);
                    result.put("error", "cannot_write_token");
                    return result.toString();
                }
                target.put("last_used_at_ms", System.currentTimeMillis());
                vault.put("active_account_id", targetId);
                writePrivateAtomic(vaultFile, vault.toString(2));
                result.put("ok", true);
                result.put("accountId", targetId);
                result.put("email", target.optString("email", ""));
                result.put("accounts", listAccountsLocked(tokenFile, vaultFile));
            } catch (Exception e) {
                Log.w(TAG, "activateAccount failed: " + e.getMessage());
                try {
                    result.put("ok", false);
                    result.put("error", "activate_failed");
                } catch (Exception ignored) {
                }
            }
            return result.toString();
        }
    }

    /** Deletes a saved account from the vault and returns the updated public account list JSON. */
    public static String removeAccount(Context context, String accountId) {
        return removeAccount(OAuthTokenStore.tokenFile(context), vaultFile(context), accountId);
    }

    public static String removeAccount(File tokenFile, File vaultFile, String accountId) {
        synchronized (LOCK) {
            try {
                if (vaultFile == null) {
                    return "[]";
                }
                String targetId = accountId != null ? accountId.trim() : "";
                if (!targetId.isEmpty()) {
                    JSONObject vault = readVaultLocked(vaultFile);
                    JSONArray accounts = vault.optJSONArray("accounts");
                    JSONArray next = new JSONArray();
                    if (accounts != null) {
                        for (int i = 0; i < accounts.length(); i++) {
                            JSONObject item = accounts.optJSONObject(i);
                            if (item != null && !targetId.equals(item.optString("id", ""))) {
                                next.put(item);
                            }
                        }
                    }
                    vault.put("accounts", next);
                    if (targetId.equals(vault.optString("active_account_id", ""))) {
                        vault.put("active_account_id", "");
                    }
                    writePrivateAtomic(vaultFile, vault.toString(2));
                }
            } catch (Exception e) {
                Log.w(TAG, "removeAccount failed: " + e.getMessage());
            }
            return listAccountsLocked(tokenFile, vaultFile).toString();
        }
    }

    private static void syncCurrentAccountLocked(File tokenFile, File vaultFile, String profileJson) {
        if (tokenFile == null || vaultFile == null) {
            return;
        }
        String rawToken = readFileTextBounded(tokenFile);
        if (!isValidTokenJson(rawToken)) {
            return;
        }
        try {
            JSONObject profile = parseJsonObjectSafe(profileJson);
            String email = firstNonEmpty(profile.optString("email", ""));
            String name = firstNonEmpty(profile.optString("name", ""));
            String avatarUrl = firstNonEmpty(profile.optString("avatarUrl", ""), profile.optString("avatar_url", ""));
            String tier = firstNonEmpty(profile.optString("tier", ""));
            String projectId = firstNonEmpty(profile.optString("projectId", ""), profile.optString("project_id", ""));
            String location = firstNonEmpty(profile.optString("location", ""));
            boolean isGcpTos = profile.optBoolean("isGcpTos", profile.optBoolean("is_gcp_tos", false));

            JSONObject tokenObj = parseJsonObjectSafe(rawToken);
            String id = computeAccountId(email, projectId, tokenObj, rawToken);
            if (id.isEmpty()) {
                return;
            }

            JSONObject vault = readVaultLocked(vaultFile);
            JSONArray accounts = vault.optJSONArray("accounts");
            if (accounts == null) {
                accounts = new JSONArray();
                vault.put("accounts", accounts);
            }

            long now = System.currentTimeMillis();
            String tokenSignature = tokenFingerprint(tokenObj, rawToken);
            JSONObject match = null;
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                if (id.equals(item.optString("id", ""))
                        || (!tokenSignature.isEmpty() && tokenSignature.equals(item.optString("token_fp", "")))) {
                    match = item;
                    break;
                }
            }
            if (match == null && !email.isEmpty()) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject item = accounts.optJSONObject(i);
                    if (item != null && email.equalsIgnoreCase(item.optString("email", ""))
                            && item.optString("project_id", "").isEmpty()) {
                        match = item;
                        break;
                    }
                }
            }

            if (match == null) {
                match = new JSONObject();
                match.put("id", id);
                match.put("added_at_ms", now);
                accounts.put(match);
            } else if (!email.isEmpty() && match.optString("id", "").startsWith("tok|")) {
                match.put("id", id);
            }

            if (!email.isEmpty()) {
                match.put("email", email);
            }
            if (!name.isEmpty()) {
                match.put("name", name);
            }
            if (!avatarUrl.isEmpty()) {
                match.put("avatar_url", avatarUrl);
            }
            if (!tier.isEmpty()) {
                match.put("tier", tier);
            }
            if (!projectId.isEmpty()) {
                match.put("project_id", projectId);
            }
            if (!location.isEmpty()) {
                match.put("location", location);
            }
            if (profile.has("isGcpTos") || profile.has("is_gcp_tos")) {
                match.put("is_gcp_tos", isGcpTos);
            }
            match.put("token_json", rawToken);
            match.put("token_fp", tokenSignature);
            match.put("last_used_at_ms", now);

            vault.put("version", VAULT_VERSION);
            vault.put("active_account_id", match.optString("id", id));
            writePrivateAtomic(vaultFile, vault.toString(2));
        } catch (Exception e) {
            Log.w(TAG, "syncCurrentAccount failed: " + e.getMessage());
        }
    }

    private static JSONArray listAccountsLocked(File tokenFile, File vaultFile) {
        JSONArray out = new JSONArray();
        if (vaultFile == null) {
            return out;
        }
        try {
            JSONObject vault = readVaultLocked(vaultFile);
            JSONArray raw = vault.optJSONArray("accounts");
            if (raw == null || raw.length() == 0) {
                return out;
            }
            final String activeId = resolveActiveAccountId(tokenFile, vault);
            List<JSONObject> items = new ArrayList<>();
            for (int i = 0; i < raw.length(); i++) {
                JSONObject item = raw.optJSONObject(i);
                if (item != null && !item.optString("id", "").isEmpty()) {
                    items.add(item);
                }
            }
            Collections.sort(items, new Comparator<JSONObject>() {
                @Override
                public int compare(JSONObject a, JSONObject b) {
                    boolean aActive = activeId.equals(a.optString("id", ""));
                    boolean bActive = activeId.equals(b.optString("id", ""));
                    if (aActive != bActive) {
                        return aActive ? -1 : 1;
                    }
                    long aLast = a.optLong("last_used_at_ms", 0L);
                    long bLast = b.optLong("last_used_at_ms", 0L);
                    if (aLast != bLast) {
                        return Long.compare(bLast, aLast);
                    }
                    return a.optString("email", "").compareToIgnoreCase(b.optString("email", ""));
                }
            });
            for (JSONObject item : items) {
                out.put(toPublicAccount(item, activeId));
            }
        } catch (Exception e) {
            Log.w(TAG, "listAccountsLocked failed: " + e.getMessage());
        }
        return out;
    }

    private static String resolveActiveAccountId(File tokenFile, JSONObject vault) {
        String currentRaw = readFileTextBounded(tokenFile);
        if (!isValidTokenJson(currentRaw)) {
            return vault.optString("active_account_id", "");
        }
        String currentFp = tokenFingerprint(parseJsonObjectSafe(currentRaw), currentRaw);
        JSONArray accounts = vault.optJSONArray("accounts");
        if (accounts != null && !currentFp.isEmpty()) {
            for (int i = 0; i < accounts.length(); i++) {
                JSONObject item = accounts.optJSONObject(i);
                if (item != null && currentFp.equals(item.optString("token_fp", ""))) {
                    return item.optString("id", "");
                }
            }
        }
        return vault.optString("active_account_id", "");
    }

    private static JSONObject toPublicAccount(JSONObject item, String activeId) throws Exception {
        JSONObject pub = new JSONObject();
        String id = item.optString("id", "");
        pub.put("id", id);
        pub.put("email", item.optString("email", ""));
        pub.put("name", item.optString("name", ""));
        pub.put("avatarUrl", item.optString("avatar_url", ""));
        pub.put("tier", item.optString("tier", ""));
        pub.put("projectId", item.optString("project_id", ""));
        pub.put("location", item.optString("location", ""));
        pub.put("isGcpTos", item.optBoolean("is_gcp_tos", false));
        pub.put("addedAtMs", item.optLong("added_at_ms", 0L));
        pub.put("lastUsedAtMs", item.optLong("last_used_at_ms", 0L));
        pub.put("active", !id.isEmpty() && id.equals(activeId));
        return pub;
    }

    private static JSONObject readVaultLocked(File file) {
        String raw = readFileTextBounded(file);
        if (raw != null && !raw.trim().isEmpty()) {
            try {
                JSONObject obj = new JSONObject(raw);
                if (obj.optJSONArray("accounts") != null) {
                    return obj;
                }
            } catch (Exception ignored) {
            }
        }
        JSONObject empty = new JSONObject();
        try {
            empty.put("version", VAULT_VERSION);
            empty.put("accounts", new JSONArray());
            empty.put("active_account_id", "");
        } catch (Exception ignored) {
        }
        return empty;
    }

    private static String readFileTextBounded(File file) {
        if (file == null || !file.isFile() || file.length() == 0 || file.length() > MAX_FILE_BYTES) {
            return null;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            return new String(IoUtil.readAllBytes(in), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean writePrivateAtomic(File target, String content) {
        if (target == null || content == null) {
            return false;
        }
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try {
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            try {
                Os.chmod(tmp.getAbsolutePath(), 0600);
            } catch (Throwable ignored) {
            }
            if (!tmp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!tmp.renameTo(target)) {
                    return false;
                }
            }
            try {
                Os.chmod(target.getAbsolutePath(), 0600);
            } catch (Throwable ignored) {
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "writePrivateAtomic failed for " + target + ": " + e.getMessage());
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
    }

    private static boolean isValidTokenJson(String raw) {
        return raw != null && !raw.trim().isEmpty() && !OAuthTokenStore.isNullOrBlankToken(raw);
    }

    private static String computeAccountId(String email, String projectId, JSONObject tokenObj, String rawToken) {
        String cleanEmail = email != null ? email.trim().toLowerCase(Locale.US) : "";
        String cleanProj = projectId != null ? projectId.trim() : "";
        if (!cleanEmail.isEmpty()) {
            return cleanProj.isEmpty() ? cleanEmail : (cleanEmail + "|" + cleanProj);
        }
        String fp = tokenFingerprint(tokenObj, rawToken);
        return fp.isEmpty() ? "" : ("tok|" + fp.substring(0, Math.min(16, fp.length())));
    }

    private static String tokenFingerprint(JSONObject tokenObj, String rawToken) {
        String seed = "";
        if (tokenObj != null) {
            JSONObject inner = tokenObj.optJSONObject("token");
            if (inner != null) {
                seed = firstNonEmpty(inner.optString("refresh_token", ""),
                        inner.optString("access_token", ""),
                        inner.optString("id_token", ""));
            }
            if (seed.isEmpty()) {
                seed = firstNonEmpty(tokenObj.optString("refresh_token", ""),
                        tokenObj.optString("access_token", ""),
                        tokenObj.optString("token", ""));
            }
        }
        if (seed.isEmpty() && rawToken != null) {
            seed = rawToken.trim();
        }
        if (seed.isEmpty()) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(dig.length * 2);
            for (byte b : dig) {
                sb.append(Character.forDigit((b >>> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(seed.hashCode());
        }
    }

    private static JSONObject parseJsonObjectSafe(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String firstNonEmpty(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (String c : candidates) {
            if (c != null && !c.trim().isEmpty()) {
                return c.trim();
            }
        }
        return "";
    }
}
