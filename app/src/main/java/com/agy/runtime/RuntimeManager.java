package com.agy.runtime;

import android.content.Context;

import com.agy.core.CoreEnvironment;
import com.agy.core.CoreRuntimeState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unpacks the bundled native runtime (assets/runtime) and web UI (assets/web)
 * into getFilesDir(), verifies the runtime manifest hashes (fail-closed), and
 * describes how to launch the patched core through the glibc loader.
 *
 * targetSdk 28 is required so the loader/core can be exec'd from the data dir.
 */
public final class RuntimeManager {

    public static final String CORE_VERSION = RuntimeConfig.CORE_VERSION;
    private static volatile Boolean cachedNativeLseSupported;

    private final Context appContext;
    private final String csrfToken;
    private final RuntimePaths paths;
    private final NetworkMonitorHolder network;
    /** Guards against starting the background env install more than once. */
    private final AtomicBoolean envInstallStarted = new AtomicBoolean(false);

    public RuntimeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.csrfToken = UUID.randomUUID().toString();
        this.paths = new RuntimePaths(context);
        this.network = new NetworkMonitorHolder(context);
    }

    public Context appContext() {
        return appContext;
    }

    public int getHttpsPort() {
        return RuntimeConfig.httpsPort(appContext);
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public File getRuntimeDir() {
        return paths.getRuntimeDir();
    }

    public File getWebDir() {
        return paths.getWebDir();
    }

    public File getBinDir() {
        return paths.getBinDir();
    }

    public File getLibDir() {
        return paths.getLibDir();
    }

    public File getCertFile() {
        return paths.getCertFile();
    }

    public File getGeminiDir() {
        return paths.getGeminiDir();
    }

    public File getAppDataDir() {
        return paths.getAppDataDir();
    }

    public File getProjectsDir() {
        return paths.getProjectsDir();
    }

    /**
     * Phase 1: extract {@code assets/runtime} (the core + glibc) and the web UI,
     * verify the runtime manifest sha256 (fail-closed), and prepare DNS/onboarding.
     * Idempotent: a runtime whose marker is present and whose manifest still
     * verifies is reused.
     */
    public synchronized void installCore() {
        CoreInstaller.installCore(this);
    }

    /**
     * Phase 2: extract the bionic toolchain and regenerate {@code runtime/bin}
     * bridges on a daemon thread, in parallel with core startup. Best-effort.
     */
    public void installEnvInBackground() {
        EnvInstallScheduler.schedule(this, envInstallStarted);
    }

    /** Loader + flags + env required to boot the patched core. */
    public String[] coreCommand() {
        File bin = getBinDir();
        File lib = getLibDir();
        File loader = new File(bin, RuntimePaths.LOADER_NAME);
        File core = new File(bin, RuntimePaths.CORE_NAME);
        File lseCompat = new File(lib, "liblse_compat.so");
        if (!loader.isFile()) {
            throw new IllegalStateException("glibc loader missing: " + loader);
        }
        if (!core.isFile()) {
            throw new IllegalStateException("core binary missing: " + core);
        }

        java.util.List<String> cmd = new java.util.ArrayList<>();
        cmd.add(loader.getAbsolutePath());
        cmd.add("--library-path");
        cmd.add(lib.getAbsolutePath());
        if (lseCompat.isFile() && !isNativeLseSupported()) {
            cmd.add("--preload");
            cmd.add(lseCompat.getAbsolutePath());
        }
        cmd.add(core.getAbsolutePath());
        cmd.add("--standalone");
        cmd.add("--override_ide_name");
        cmd.add("antigravity");
        cmd.add("--subclient_type");
        cmd.add("hub");
        cmd.add("--override_ide_version");
        cmd.add(CORE_VERSION);
        cmd.add("--override_user_agent_name");
        cmd.add("antigravity");
        cmd.add("--https_server_port");
        cmd.add(String.valueOf(getHttpsPort()));
        cmd.add("--csrf_token");
        cmd.add(csrfToken);
        // Stable across builds: HOME/.gemini/<APP_DATA_DIR_NAME>.
        cmd.add("--app_data_dir");
        cmd.add(RuntimePaths.APP_DATA_DIR_NAME);
        cmd.add("--web_bundle_path");
        cmd.add(getWebDir().getAbsolutePath());
        if (CoreEnvironment.isSidecarsSupported(appContext)) {
            cmd.add("--enable_sidecars");
        }

        return cmd.toArray(new String[0]);
    }

    public void applyEnvironment(ProcessBuilder pb) {
        CoreEnvironment.apply(this, pb);
    }

    // ---- network config ---------------------------------------------------

    /** Start watching the default network; on changes the DNS config is rewritten. */
    public void startNetworkMonitor() {
        network.start();
    }

    /** Unregisters the process network callback when the core service stops. */
    public void stopNetworkMonitor() {
        network.unregister();
    }

    /**
     * Refresh DNS config in place. Rewriting resolv.conf is enough for a running
     * core: it uses GODEBUG=netdns=go and re-reads the file, so the process is
     * not restarted.
     */
    public synchronized void refreshNetwork() {
        writeNetworkConfig();
        if (CoreRuntimeState.serverUrl != null) {
            android.util.Log.d("RuntimeManager", "network changed; rewrote DNS config (core stays up)");
        } else {
            android.util.Log.d("RuntimeManager", "network changed; DNS config refreshed");
        }
    }

    public synchronized void writeNetworkConfig() {
        NetworkConfigWriter.write(appContext);
    }

    /** Returns true iff /proc/cpuinfo advertises all ARMv8.1-8.4 features emulated by liblse_compat.so. */
    public static boolean cpuSupportsAllEmulatedFeatures(String cpuInfoText) {
        if (cpuInfoText == null || cpuInfoText.isEmpty()) {
            return false;
        }
        Set<String> features = new HashSet<>();
        String[] lines = cpuInfoText.split("\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            if (!"Features".equals(key)) {
                continue;
            }
            String[] tokens = line.substring(colon + 1).trim().split("\\s+");
            for (String tok : tokens) {
                if (!tok.isEmpty()) {
                    features.add(tok);
                }
            }
        }
        return features.contains("atomics")
                && features.contains("asimddp")
                && features.contains("asimdrdm")
                && features.contains("fcma")
                && features.contains("lrcpc")
                && features.contains("ilrcpc");
    }

    private static boolean isNativeLseSupported() {
        Boolean cached = cachedNativeLseSupported;
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean supported = false;
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Features")) {
                    sb.append(line).append('\n');
                    break;
                }
            }
            supported = cpuSupportsAllEmulatedFeatures(sb.toString());
        } catch (Throwable ignored) {
            supported = false;
        }
        cachedNativeLseSupported = Boolean.valueOf(supported);
        return supported;
    }
}
