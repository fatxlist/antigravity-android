package com.agy;

import com.agy.gateway.SystemGatewayServer;
import com.agy.oauth.OAuthTokenStore;
import com.agy.runtime.OnboardingSeeder;
import com.agy.util.AppFileProvider;
import com.agy.util.DocumentsContractHelper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Pure JVM unit tests for security helpers and path/token logic that do not
 * depend on live Android framework services.
 */
public final class UnitTests {

    private static int passed = 0;

    private static void check(String name, boolean cond) {
        if (!cond) {
            throw new AssertionError("FAILED: " + name);
        }
        passed++;
    }

    private static void checkEquals(String name, String expected, String actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("FAILED: " + name + " expected=<" + expected + "> actual=<" + actual + ">");
        }
        passed++;
    }

    private static void testGatewayAuth() {
        String expected = "0123456789abcdef0123456789abcdef";

        Map<String, String> direct = new HashMap<>();
        direct.put("x-agy-gateway-token", expected);
        checkEquals("extract direct header", expected, SystemGatewayServer.extractTokenFromHeaders(direct));
        check("authorize direct header", SystemGatewayServer.isAuthorized(expected, direct));

        Map<String, String> mixedHeader = new HashMap<>();
        mixedHeader.put("X-Agy-Gateway-Token", "  " + expected + "  ");
        check("authorize mixed-case header key", SystemGatewayServer.isAuthorized(expected, mixedHeader));

        Map<String, String> bearer = new HashMap<>();
        bearer.put("Authorization", "bEaReR " + expected);
        checkEquals("extract bearer header", expected, SystemGatewayServer.extractTokenFromHeaders(bearer));
        check("authorize bearer header", SystemGatewayServer.isAuthorized(expected, bearer));

        Map<String, String> wrong = new HashMap<>();
        wrong.put("x-agy-gateway-token", "ffffffffffffffffffffffffffffffff");
        check("reject wrong token", !SystemGatewayServer.isAuthorized(expected, wrong));

        check("reject empty headers", !SystemGatewayServer.isAuthorized(expected, Collections.<String, String>emptyMap()));
        check("reject null headers", !SystemGatewayServer.isAuthorized(expected, null));
        check("reject blank expected token", !SystemGatewayServer.isAuthorized("", direct));
        check("reject null expected token", !SystemGatewayServer.isAuthorized(null, direct));
    }

    private static void testOAuthTokenStore() {
        check("null text is blank", OAuthTokenStore.isNullOrBlankToken(null));
        check("empty text is blank", OAuthTokenStore.isNullOrBlankToken("   "));
        check("missing token field is blank", OAuthTokenStore.isNullOrBlankToken("{\"ok\":true}"));
        check("null json value is blank", OAuthTokenStore.isNullOrBlankToken("{\"token\": null}"));
        check("empty string json value is blank", OAuthTokenStore.isNullOrBlankToken("{\"token\": \"\"}"));
        check("real token is accepted", !OAuthTokenStore.isNullOrBlankToken("{\"token\": \"ya29.abc\"}"));
    }

    private static void testAppFileProviderDenyList() {
        String files = "/data/user/0/com.agy/files";
        String cache = "/data/user/0/com.agy/cache";
        String ext = "/storage/emulated/0";
        String[] roots = new String[]{files, cache, ext};

        check("allow workspace file", AppFileProvider.isAllowedPath(files + "/workspace/app.py", files, roots));
        check("allow cache file", AppFileProvider.isAllowedPath(cache + "/tmp.txt", files, roots));
        check("allow external storage file", AppFileProvider.isAllowedPath(ext + "/Download/report.txt", files, roots));

        check("deny .gemini dir", !AppFileProvider.isAllowedPath(files + "/.gemini", files, roots));
        check("deny .gemini nested", !AppFileProvider.isAllowedPath(files + "/.gemini/antigravity/oauth_creds.json", files, roots));
        check("deny gateway.token", !AppFileProvider.isAllowedPath(files + "/logs/gateway.token", files, roots));
        check("deny core.state.json", !AppFileProvider.isAllowedPath(files + "/logs/core.state.json", files, roots));
        check("deny approvals.json", !AppFileProvider.isAllowedPath(files + "/logs/approvals.json", files, roots));
        check("deny shared_prefs", !AppFileProvider.isAllowedPath("/data/user/0/com.agy/shared_prefs/agy_prefs.xml", files, roots));
        check("deny databases", !AppFileProvider.isAllowedPath("/data/user/0/com.agy/databases/main.db", files, roots));
        check("deny outside root", !AppFileProvider.isAllowedPath("/data/user/0/other.app/files/x", files, roots));
        check("allow non-sensitive log file", AppFileProvider.isAllowedPath(files + "/logs/core.log", files, roots));
    }

    private static void testDocumentsContractHelper() {
        String primary = "/storage/emulated/0";
        checkEquals("primary subfolder",
                "/storage/emulated/0/Documents/proj",
                DocumentsContractHelper.resolveDocumentId("com.android.externalstorage.documents", "primary:Documents/proj", primary));
        checkEquals("primary root",
                "/storage/emulated/0",
                DocumentsContractHelper.resolveDocumentId("com.android.externalstorage.documents", "primary:", primary));
        checkEquals("raw path",
                "/storage/emulated/0/Download/repo",
                DocumentsContractHelper.resolveDocumentId("com.android.providers.downloads.documents", "raw:/storage/emulated/0/Download/repo", primary));
        checkEquals("home volume",
                "/storage/emulated/0/Documents/Notes",
                DocumentsContractHelper.resolveDocumentId("com.android.externalstorage.documents", "home:Notes", primary));
        checkEquals("external sd volume",
                "/storage/1234-5678/Work",
                DocumentsContractHelper.resolveDocumentId("com.android.externalstorage.documents", "1234-5678:Work", primary));
        checkEquals("downloads root",
                "/storage/emulated/0/Download",
                DocumentsContractHelper.resolveDocumentId("com.android.providers.downloads.documents", "downloads", primary));
        check("opaque msd downloads id returns null",
                DocumentsContractHelper.resolveDocumentId("com.android.providers.downloads.documents", "msd:42", primary) == null);
        check("opaque numeric downloads id returns null",
                DocumentsContractHelper.resolveDocumentId("com.android.providers.downloads.documents", "12345", primary) == null);
    }

    private static void testOnboardingSeederUuid() {
        String tpl = OnboardingSeeder.TEMPLATE_INSTALL_UUID;
        String sample = "agent_onboarding_completed: true\ninstallation_uuid: \"" + tpl + "\"\nother: 1\n";
        String unique = "11111111-2222-4333-8444-555555555555";
        String out = OnboardingSeeder.replaceTemplateUuid(sample, unique);
        check("template uuid replaced", !out.contains(tpl) && out.contains("installation_uuid: \"" + unique + "\""));
        check("unrelated content preserved", out.contains("agent_onboarding_completed: true") && out.contains("other: 1"));
        String custom = "installation_uuid: \"aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee\"\n";
        checkEquals("already-unique uuid untouched", custom, OnboardingSeeder.replaceTemplateUuid(custom, unique));
    }

    public static void main(String[] args) {
        testGatewayAuth();
        testOAuthTokenStore();
        testAppFileProviderDenyList();
        testDocumentsContractHelper();
        testOnboardingSeederUuid();
        System.out.println("[OK] Java unit tests passed (" + passed + " checks).");
    }
}
