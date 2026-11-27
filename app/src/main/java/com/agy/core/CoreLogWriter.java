package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Appends core stdout to files/logs/core.log. */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    private static final int BUFFER_BYTES = 16 * 1024;
    private static final long FLUSH_INTERVAL_MS = 500L;
    private static volatile long lastFlushMs = 0L;

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            File currentLog = new File(dir, "core.log");
            if (currentLog.isFile() && currentLog.length() > 0) {
                File prevLog = new File(dir, "core.prev.log");
                if (prevLog.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    prevLog.delete();
                }
                //noinspection ResultOfMethodCallIgnored
                currentLog.renameTo(prevLog);
            }
            lastFlushMs = System.currentTimeMillis();
            return new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(currentLog, false), StandardCharsets.UTF_8),
                    BUFFER_BYTES);
        } catch (Exception e) {
            Log.w(TAG, "cannot open core log", e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        writeRedactedLine(log, SensitiveDataRedactor.redactLine(line), true);
    }

    /** Writes an already-redacted line to the buffered core log, flushing on demand or every 500 ms. */
    public static void writeRedactedLine(Writer log, String safeLine, boolean flushNow) {
        if (log == null || safeLine == null) {
            return;
        }
        try {
            log.write(safeLine);
            log.write('\n');
            long now = System.currentTimeMillis();
            if (flushNow || (now - lastFlushMs) >= FLUSH_INTERVAL_MS) {
                log.flush();
                lastFlushMs = now;
            }
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
