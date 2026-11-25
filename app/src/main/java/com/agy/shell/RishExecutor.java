package com.agy.shell;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs a shell command through the bundled rish bridge with bounded timeout and output. */
public final class RishExecutor {

    private static final long TIMEOUT_SECONDS = 60;
    private static final int MAX_OUTPUT_BYTES = 256 * 1024;

    private final Context appContext;
    private final RishLocator locator;

    public RishExecutor(Context context) {
        this.appContext = context.getApplicationContext();
        this.locator = new RishLocator(context);
    }

    /** Runs `cmd` as shell via rish. Returns combined stdout/stderr; errors are text. */
    public String exec(String cmd) {
        if (cmd == null || cmd.trim().isEmpty()) {
            return "error: empty command";
        }
        File rish = locator.rishBinary();
        if (!rish.isFile()) {
            return "error: bundled rish not found at " + rish.getAbsolutePath();
        }
        if (!rish.canExecute()) {
            return "error: rish not executable: " + rish.getAbsolutePath()
                    + " (Shizuku may be unavailable)";
        }
        ProcessBuilder pb = new ProcessBuilder(
                rish.getAbsolutePath(),
                "-c",
                "env -u LD_LIBRARY_PATH " + cmd);
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.remove("LD_LIBRARY_PATH");
        env.put("RISH_APPLICATION_ID", appContext.getPackageName());
        pb.directory(appContext.getFilesDir());
        Process process = null;
        Thread drainThread = null;
        try {
            process = pb.start();
            final InputStream in = process.getInputStream();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final boolean[] truncated = new boolean[]{false};
            drainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    byte[] buf = new byte[4096];
                    try {
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            int remaining = MAX_OUTPUT_BYTES - out.size();
                            if (remaining > 0) {
                                int toWrite = Math.min(n, remaining);
                                out.write(buf, 0, toWrite);
                                if (toWrite < n) {
                                    truncated[0] = true;
                                }
                            } else {
                                truncated[0] = true;
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, "RishExecutor-drain");
            drainThread.setDaemon(true);
            drainThread.start();

            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                drainThread.interrupt();
                drainThread.join(1000);
                return "error: rish timed out after " + TIMEOUT_SECONDS + "s";
            }
            drainThread.join(2000);
            int code = process.exitValue();
            String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
            if (truncated[0]) {
                text = text.isEmpty() ? "[output truncated]" : (text + "\n[output truncated]");
            }
            if (code != 0 && text.isEmpty()) {
                return "error: rish exited " + code;
            }
            return text;
        } catch (Exception e) {
            return "error: " + e.getMessage();
        } finally {
            if (drainThread != null) {
                drainThread.interrupt();
            }
            if (process != null) {
                process.destroyForcibly();
            }
        }
    }
}
