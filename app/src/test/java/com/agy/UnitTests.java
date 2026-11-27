package com.agy;

import com.agy.core.CoreEnvironment;
import com.agy.core.CoreEventWatcher;
import com.agy.core.CoreServerService;
import com.agy.core.VoiceForegroundService;
import com.agy.gateway.SystemGatewayServer;
import com.agy.gateway.handlers.DoctorHandler;
import com.agy.oauth.OAuthTokenStore;
import com.agy.runtime.OnboardingSeeder;
import com.agy.runtime.RuntimeManager;
import com.agy.theme.EdgeToEdge;
import com.agy.theme.ThemeColors;
import com.agy.ui.MainActivity;
import com.agy.ui.download.FileDownloader;
import com.agy.ui.extra.VoiceDictationController;
import com.agy.ui.webview.AppHtmlInjector;
import com.agy.util.AppFileProvider;
import com.agy.util.DocumentsContractHelper;
import com.agy.util.HostActivityResolver;
import com.agy.util.SensitiveDataRedactor;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    private static void testEdgeToEdge() {
        check("API 24 light nav scrim",
                EdgeToEdge.resolveNavigationBarColor(24, false) == EdgeToEdge.LEGACY_LIGHT_SCRIM);
        check("API 24 dark transparent nav",
                EdgeToEdge.resolveNavigationBarColor(24, true) == 0);
        check("API 26 light transparent nav",
                EdgeToEdge.resolveNavigationBarColor(26, false) == 0);
        check("API 30 light transparent nav",
                EdgeToEdge.resolveNavigationBarColor(30, false) == 0);

        EdgeToEdge.InsetsRect imeClosed = EdgeToEdge.computeEffectiveInsets(
                0, 96, 0, 48,
                16, 120, 0, 0,
                0);
        check("effective left max(sys, cutout)", imeClosed.left == 16);
        check("effective top max(sys, cutout)", imeClosed.top == 120);
        check("effective right max(sys, cutout)", imeClosed.right == 0);
        check("effective navBottom", imeClosed.navBottom == 48);
        check("effective bottom when IME closed", imeClosed.bottom == 48);
        check("effective imeBottom when IME closed", imeClosed.imeBottom == 0);

        EdgeToEdge.InsetsRect imeOpen = EdgeToEdge.computeEffectiveInsets(
                0, 96, 0, 48,
                0, 0, 0, 20,
                840);
        check("effective top when IME open", imeOpen.top == 96);
        check("effective navBottom with bottom cutout", imeOpen.navBottom == 48);
        check("effective bottom uses IME when larger", imeOpen.bottom == 840);
        check("effective imeBottom preserved", imeOpen.imeBottom == 840);

        check("ime bottom padding when IME closed",
                EdgeToEdge.computeImeBottomPadding(0, 48) == 0);
        check("ime bottom padding when IME <= navBottom",
                EdgeToEdge.computeImeBottomPadding(40, 48) == 0);
        check("ime bottom padding subtracts navBottom",
                EdgeToEdge.computeImeBottomPadding(840, 48) == 792);
        check("ime bottom padding with zero navBottom",
                EdgeToEdge.computeImeBottomPadding(600, 0) == 600);

        EdgeToEdge.InsetsRect legacyNav = EdgeToEdge.computeLegacyInsets(
                0, 84, 0, 48, 48,
                0, 84, 0, 0,
                300);
        check("legacy nav only sysBottom stays navBottom", legacyNav.navBottom == 48);
        check("legacy nav only imeBottom is zero", legacyNav.imeBottom == 0);

        EdgeToEdge.InsetsRect legacyIme = EdgeToEdge.computeLegacyInsets(
                0, 84, 0, 880, 48,
                0, 84, 0, 0,
                300);
        check("legacy IME preserves stable navBottom", legacyIme.navBottom == 48);
        check("legacy IME exposes full imeBottom", legacyIme.imeBottom == 880);
        check("legacy IME top cutout preserved", legacyIme.top == 84);
    }

    private static void testCoreEventWatcherFatalFilter() {
        check("ignore lse_compat INIT SIGILL handler line",
                !CoreEventWatcher.isFatalErrorLine("[lse_compat] INIT: installing kernel SIGILL handler"));
        check("ignore timestamped lse_compat INIT line",
                !CoreEventWatcher.isFatalErrorLine("2026/11/26 [lse_compat] INIT: installing kernel SIGILL handler"));
        check("ignore lse_compat SUCCESS SIGILL handler line",
                !CoreEventWatcher.isFatalErrorLine("[lse_compat] SUCCESS: SIGILL handler active in Linux kernel"));
        check("flag lse_compat FATAL unhandled SIGILL",
                CoreEventWatcher.isFatalErrorLine("[lse_compat] FATAL: unhandled SIGILL at PC=0x1234"));
        check("flag lse_compat ERROR raw_rt_sigaction",
                CoreEventWatcher.isFatalErrorLine("[lse_compat] ERROR: raw_rt_sigaction failed with code: -22"));
        check("flag panic line",
                CoreEventWatcher.isFatalErrorLine("panic: runtime error: invalid memory address"));
        check("flag fatal error line",
                CoreEventWatcher.isFatalErrorLine("fatal error: all goroutines are asleep - deadlock!"));
        check("flag SIGSEGV crash line",
                CoreEventWatcher.isFatalErrorLine("SIGSEGV: segmentation violation"));
        check("flag bare SIGILL crash line",
                CoreEventWatcher.isFatalErrorLine("Program received signal SIGILL at 0xdeadbeef"));
        check("ignore null or empty",
                !CoreEventWatcher.isFatalErrorLine(null) && !CoreEventWatcher.isFatalErrorLine(""));
    }

    private static void testRequestCodeUniqueness() {
        int[] codes = new int[]{
                VoiceDictationController.REQUEST_RECORD_AUDIO_DICTATION,
                VoiceDictationController.REQUEST_VOICE_DICTATION,
                FileDownloader.REQUEST_STORAGE_DOWNLOAD,
                MainActivity.REQUEST_DIR_CHOOSER,
                MainActivity.REQUEST_POST_NOTIFICATIONS
        };
        Set<Integer> unique = new HashSet<>();
        for (int code : codes) {
            unique.add(code);
        }
        check("all activity/permission request codes are distinct", unique.size() == codes.length);
    }

    private static void testThemeColors() {
        Integer dark = ThemeColors.parseHexColor("#101010");
        check("parse #101010", dark != null && dark.intValue() == 0xFF101010);
        Integer whiteShort = ThemeColors.parseHexColor("#fff");
        check("parse #fff", whiteShort != null && whiteShort.intValue() == 0xFFFFFFFF);
        Integer slate = ThemeColors.parseHexColor(" #0b0e17 ");
        check("parse trimmed #0b0e17", slate != null && slate.intValue() == 0xFF0B0E17);
        check("reject invalid hex", ThemeColors.parseHexColor("invalid") == null);
        check("reject 5-digit hex", ThemeColors.parseHexColor("#12345") == null);
        check("reject null hex", ThemeColors.parseHexColor(null) == null);

        check("#101010 is dark", ThemeColors.isDarkColor(0xFF101010));
        check("#0b0e17 is dark", ThemeColors.isDarkColor(0xFF0B0E17));
        check("#ffffff is light", !ThemeColors.isDarkColor(0xFFFFFFFF));
        check("#f3f4f6 is light", !ThemeColors.isDarkColor(0xFFF3F4F6));
    }

    private static File createTempTestDir(String prefix) throws Exception {
        String envTmp = System.getenv("TMPDIR");
        File baseTmp = (envTmp != null && !envTmp.isEmpty() && new File(envTmp).isDirectory())
                ? new File(envTmp)
                : new File(System.getProperty("java.io.tmpdir"));
        return Files.createTempDirectory(baseTmp.toPath(), prefix).toFile();
    }

    private static void testCoreServerDaemonEnv() throws Exception {
        File tmpFiles = createTempTestDir("agy_daemon_env_test_");
        try {
            File usrBin = new File(tmpFiles, "usr/bin");
            File usrLib = new File(tmpFiles, "usr/lib");
            File tlsDir = new File(tmpFiles, "usr/etc/tls");
            usrBin.mkdirs();
            usrLib.mkdirs();
            tlsDir.mkdirs();

            File preload = new File(usrLib, "libtermux-exec.so");
            preload.createNewFile();
            File cert = new File(tlsDir, "cert.pem");
            cert.createNewFile();
            File py = new File(usrBin, "python3");
            py.createNewFile();
            py.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(CoreServerService.buildDaemonCommand(tmpFiles, "start"));
            CoreServerService.configureDaemonProcess(pb, tmpFiles, "com.agy.dev", 45161);
            Map<String, String> env = pb.environment();

            checkEquals("daemon command uses python3 when executable", py.getAbsolutePath(), pb.command().get(0));
            checkEquals("daemon command targets agy-daemon", new File(usrBin, "agy-daemon").getAbsolutePath(), pb.command().get(1));
            checkEquals("daemon action preserved", "start", pb.command().get(2));
            checkEquals("daemon HOME set", tmpFiles.getAbsolutePath(), env.get("HOME"));
            checkEquals("daemon PREFIX set", new File(tmpFiles, "usr").getAbsolutePath(), env.get("PREFIX"));
            checkEquals("daemon TMPDIR set", new File(tmpFiles, "usr/tmp").getAbsolutePath(), env.get("TMPDIR"));
            check("daemon TMPDIR created", new File(tmpFiles, "usr/tmp").isDirectory());
            check("daemon PATH prefixed with usr/bin", env.get("PATH").startsWith(usrBin.getAbsolutePath()));
            check("daemon LD_LIBRARY_PATH includes usr/lib", env.get("LD_LIBRARY_PATH").contains(usrLib.getAbsolutePath()));
            checkEquals("daemon LD_PRELOAD set", preload.getAbsolutePath(), env.get("LD_PRELOAD"));
            checkEquals("daemon SSL_CERT_FILE set", cert.getAbsolutePath(), env.get("SSL_CERT_FILE"));
            checkEquals("daemon AGY_APPLICATION_ID set", "com.agy.dev", env.get("AGY_APPLICATION_ID"));
            checkEquals("daemon AGY_GATEWAY_PORT set", "45161", env.get("AGY_GATEWAY_PORT"));
        } finally {
            deleteRecursively(tmpFiles);
        }
    }

    private static void testHostActivityResolverCascadeId() {
        checkEquals("sanitizeCascadeId accepts valid id",
                "conv-123_abc.def", HostActivityResolver.sanitizeCascadeId("conv-123_abc.def"));
        checkEquals("sanitizeCascadeId trims whitespace",
                "conv-9", HostActivityResolver.sanitizeCascadeId("  conv-9  "));
        check("sanitizeCascadeId rejects traversal", HostActivityResolver.sanitizeCascadeId("../bad") == null);
        check("sanitizeCascadeId rejects slash", HostActivityResolver.sanitizeCascadeId("a/b") == null);
        check("sanitizeCascadeId rejects empty", HostActivityResolver.sanitizeCascadeId("") == null);
        check("sanitizeCascadeId rejects null", HostActivityResolver.sanitizeCascadeId(null) == null);
        StringBuilder over = new StringBuilder();
        for (int i = 0; i < 129; i++) {
            over.append('a');
        }
        check("sanitizeCascadeId rejects >128 chars", HostActivityResolver.sanitizeCascadeId(over.toString()) == null);
    }

    private static void testVoiceBluetoothDeviceSelection() {
        check("bluetooth selector returns -1 for null",
                VoiceForegroundService.selectPreferredBluetoothDeviceType(null) == -1);
        check("bluetooth selector returns -1 for empty",
                VoiceForegroundService.selectPreferredBluetoothDeviceType(new int[0]) == -1);
        check("bluetooth selector ignores non-BT devices",
                VoiceForegroundService.selectPreferredBluetoothDeviceType(new int[]{1, 2}) == -1);
        check("bluetooth selector chooses SCO when available",
                VoiceForegroundService.selectPreferredBluetoothDeviceType(
                        new int[]{2, VoiceForegroundService.DEVICE_TYPE_BLUETOOTH_SCO})
                        == VoiceForegroundService.DEVICE_TYPE_BLUETOOTH_SCO);
        check("bluetooth selector prefers BLE headset over SCO",
                VoiceForegroundService.selectPreferredBluetoothDeviceType(
                        new int[]{VoiceForegroundService.DEVICE_TYPE_BLUETOOTH_SCO, VoiceForegroundService.DEVICE_TYPE_BLE_HEADSET})
                        == VoiceForegroundService.DEVICE_TYPE_BLE_HEADSET);
    }

    private static void testDoctorDaemonPidCheck() throws Exception {
        File tmp = createTempTestDir("agy_doc_pid_test_");
        try {
            File pidFile = new File(tmp, "daemon.pid");
            File procRoot = new File(tmp, "proc");
            check("missing pidFile yields -1", DoctorHandler.readLiveDaemonPid(pidFile, procRoot) == -1L);

            File proc4321 = new File(procRoot, "4321");
            proc4321.mkdirs();
            Files.write(pidFile.toPath(), "4321\n".getBytes("UTF-8"));
            Files.write(new File(proc4321, "cmdline").toPath(),
                    "python3\0/usr/bin/agy-daemon\0start\0".getBytes("UTF-8"));
            check("live agy-daemon pid detected", DoctorHandler.readLiveDaemonPid(pidFile, procRoot) == 4321L);

            File proc4322 = new File(procRoot, "4322");
            proc4322.mkdirs();
            Files.write(pidFile.toPath(), "{\"pid\":4322}".getBytes("UTF-8"));
            Files.write(new File(proc4322, "cmdline").toPath(),
                    "python3\0/usr/bin/agy-daemon\0".getBytes("UTF-8"));
            check("live JSON agy-daemon pid detected", DoctorHandler.readLiveDaemonPid(pidFile, procRoot) == 4322L);

            File proc4323 = new File(procRoot, "4323");
            proc4323.mkdirs();
            Files.write(pidFile.toPath(), "4323\n".getBytes("UTF-8"));
            Files.write(new File(proc4323, "cmdline").toPath(),
                    "sleep\00060\0".getBytes("UTF-8"));
            check("non-daemon process cmdline rejected", DoctorHandler.readLiveDaemonPid(pidFile, procRoot) == -1L);
        } finally {
            deleteRecursively(tmp);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) {
            return;
        }
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static void testSensitiveDataRedactor() {
        checkEquals("redactLine null", null, SensitiveDataRedactor.redactLine(null));
        checkEquals("redactLine empty", "", SensitiveDataRedactor.redactLine(""));
        String clean = "2026-11-27 INFO core ready on port 45157";
        check("redactLine fast-path returns exact identity on clean log",
                SensitiveDataRedactor.redactLine(clean) == clean);
        checkEquals("redactLine secret field",
                "csrf_token=<redacted>&ok=1",
                SensitiveDataRedactor.redactLine("csrf_token=secret123&ok=1"));
        checkEquals("redactLine sensitive header",
                "Authorization: <redacted>",
                SensitiveDataRedactor.redactLine("Authorization: Bearer abc.def+123="));
        checkEquals("redactLine bearer token",
                "Header Bearer <redacted>",
                SensitiveDataRedactor.redactLine("Header Bearer tok_999+abc="));
    }

    private static void testAppHtmlInjectorAssetVersioning() {
        String html = "<head>"
                + "<link rel=\"stylesheet\" href=\"./compiled_tailwind.css\">"
                + "<script src=\"./generated/main.js\"></script>"
                + "<script src=\"./cached.js?v=1\"></script>"
                + "<script src=\"https://example.com/ext.js\"></script>"
                + "</head>";
        checkEquals("appendAssetVersion null unchanged", html, AppHtmlInjector.appendAssetVersion(html, null));
        checkEquals("appendAssetVersion empty unchanged", html, AppHtmlInjector.appendAssetVersion(html, ""));
        String versioned = AppHtmlInjector.appendAssetVersion(html, "20502_2.35.2_99");
        String expected = "<head>"
                + "<link rel=\"stylesheet\" href=\"./compiled_tailwind.css?v=20502_2.35.2_99\">"
                + "<script src=\"./generated/main.js?v=20502_2.35.2_99\"></script>"
                + "<script src=\"./cached.js?v=1\"></script>"
                + "<script src=\"https://example.com/ext.js\"></script>"
                + "</head>";
        checkEquals("appendAssetVersion local js/css only", expected, versioned);
    }

    private static void testCoreEnvironmentMobileGoTuning() {
        check("mem limit default 0", CoreEnvironment.computeGoMemLimitMiB(0L) == 512L);
        check("mem limit default negative", CoreEnvironment.computeGoMemLimitMiB(-1024L) == 512L);
        check("mem limit floor 1GiB", CoreEnvironment.computeGoMemLimitMiB(1024L * 1024L * 1024L) == 384L);
        check("mem limit quarter 3GiB", CoreEnvironment.computeGoMemLimitMiB(3L * 1024L * 1024L * 1024L) == 768L);
        check("mem limit cap 8GiB", CoreEnvironment.computeGoMemLimitMiB(8L * 1024L * 1024L * 1024L) == 1024L);

        check("gomaxprocs default 0", CoreEnvironment.computeGoMaxProcs(0) == 4);
        check("gomaxprocs floor 1", CoreEnvironment.computeGoMaxProcs(1) == 2);
        check("gomaxprocs mid 4", CoreEnvironment.computeGoMaxProcs(4) == 4);
        check("gomaxprocs cap 8", CoreEnvironment.computeGoMaxProcs(8) == 6);
    }

    private static void testRuntimeManagerCpuFeatures() {
        check("cpuinfo null", !RuntimeManager.cpuSupportsAllEmulatedFeatures(null));
        check("cpuinfo empty", !RuntimeManager.cpuSupportsAllEmulatedFeatures(""));
        String fullCpuInfo = "processor\t: 0\nBogoMIPS\t: 38.40\n"
                + "Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp "
                + "cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 sve "
                + "asimdfhm dit uscat ilrcpc flagm ssbs\n";
        check("cpuinfo all 6 emulated features supported",
                RuntimeManager.cpuSupportsAllEmulatedFeatures(fullCpuInfo));
        String missingIlrcpc = "Features\t: fp asimd atomics asimddp asimdrdm fcma lrcpc\n";
        check("cpuinfo missing ilrcpc rejected",
                !RuntimeManager.cpuSupportsAllEmulatedFeatures(missingIlrcpc));
        String wrongKey = "ProcessorFeatures\t: atomics asimddp asimdrdm fcma lrcpc ilrcpc\n";
        check("cpuinfo non-Features key ignored",
                !RuntimeManager.cpuSupportsAllEmulatedFeatures(wrongKey));
    }

    public static void main(String[] args) throws Exception {
        testGatewayAuth();
        testOAuthTokenStore();
        testAppFileProviderDenyList();
        testDocumentsContractHelper();
        testOnboardingSeederUuid();
        testEdgeToEdge();
        testCoreEventWatcherFatalFilter();
        testRequestCodeUniqueness();
        testThemeColors();
        testCoreServerDaemonEnv();
        testHostActivityResolverCascadeId();
        testVoiceBluetoothDeviceSelection();
        testDoctorDaemonPidCheck();
        testSensitiveDataRedactor();
        testAppHtmlInjectorAssetVersioning();
        testCoreEnvironmentMobileGoTuning();
        testRuntimeManagerCpuFeatures();
        System.out.println("[OK] Java unit tests passed (" + passed + " checks).");
    }
}
