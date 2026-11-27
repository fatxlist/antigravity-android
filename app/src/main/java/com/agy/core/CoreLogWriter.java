package com.agy.core;

import android.util.Log;

import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Buffered, in-session rotating log writer for {@code files/logs/core.log}.
 * Rotates {@code core.log} to {@code core.prev.log} on open (when non-empty)
 * and continuously during long sessions when {@link #maxBytes} is reached.
 */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";
    public static final long DEFAULT_MAX_BYTES = 1_500_000L;
    private static final int BUFFER_CHARS = 16 * 1024;
    private static final int FLUSH_LINE_INTERVAL = 16;
    private static final long FLUSH_TIME_INTERVAL_MS = 500L;

    private CoreLogWriter() {
    }

    public static final class RotatingWriter implements Closeable {
        private final File dir;
        private final String filename;
        private final String prevFilename;
        private final long maxBytes;
        private BufferedWriter writer;
        private long currentFileBytes;
        private int pendingLines;
        private long lastFlushMs;
        private boolean closed;

        RotatingWriter(File filesDir, String filename, String prevFilename, long maxBytes) {
            this.dir = new File(filesDir, "logs");
            this.filename = filename;
            this.prevFilename = prevFilename;
            this.maxBytes = Math.max(1024L, maxBytes);
            this.lastFlushMs = System.currentTimeMillis();
            openUnderlying(true);
        }

        private void openUnderlying(boolean rotateExisting) {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create log dir " + dir);
                    this.writer = null;
                    return;
                }
                File currentLog = new File(dir, filename);
                if (rotateExisting && currentLog.isFile() && currentLog.length() > 0) {
                    rotateFile(currentLog);
                }
                this.currentFileBytes = currentLog.isFile() ? currentLog.length() : 0L;
                this.writer = new BufferedWriter(
                        new OutputStreamWriter(new FileOutputStream(currentLog, false), StandardCharsets.UTF_8),
                        BUFFER_CHARS);
                this.pendingLines = 0;
                this.lastFlushMs = System.currentTimeMillis();
            } catch (Exception e) {
                Log.w(TAG, "cannot open core log", e);
                this.writer = null;
            }
        }

        private void rotateFile(File currentLog) {
            File prevLog = new File(dir, prevFilename);
            if (prevLog.exists()) {
                //noinspection ResultOfMethodCallIgnored
                prevLog.delete();
            }
            //noinspection ResultOfMethodCallIgnored
            currentLog.renameTo(prevLog);
        }

        public synchronized void writeRedactedLine(String safeLine, boolean flushNow) {
            if (closed || writer == null || safeLine == null) {
                return;
            }
            try {
                long lineLen = safeLine.length() + 1L;
                if (currentFileBytes > 0 && currentFileBytes + lineLen > maxBytes) {
                    writer.flush();
                    writer.close();
                    writer = null;
                    openUnderlying(true);
                    if (writer == null) {
                        return;
                    }
                }
                writer.write(safeLine);
                writer.write('\n');
                currentFileBytes += lineLen;
                pendingLines++;
                long now = System.currentTimeMillis();
                if (flushNow || pendingLines >= FLUSH_LINE_INTERVAL
                        || (now - lastFlushMs) >= FLUSH_TIME_INTERVAL_MS) {
                    writer.flush();
                    pendingLines = 0;
                    lastFlushMs = now;
                }
            } catch (Exception ignored) {
            }
        }

        public void writeLine(String rawLine) {
            if (rawLine == null) {
                return;
            }
            writeRedactedLine(SensitiveDataRedactor.redactLine(rawLine), true);
        }

        public synchronized void flushQuietly() {
            if (writer != null && !closed) {
                try {
                    writer.flush();
                    pendingLines = 0;
                    lastFlushMs = System.currentTimeMillis();
                } catch (IOException ignored) {
                }
            }
        }

        @Override
        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            if (writer != null) {
                try {
                    writer.flush();
                } catch (Exception ignored) {
                }
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
                writer = null;
            }
        }
    }

    public static RotatingWriter open(File filesDir) {
        return open(filesDir, "core.log", DEFAULT_MAX_BYTES);
    }

    public static RotatingWriter open(File filesDir, String filename, long maxBytes) {
        String prevName = "core.log".equals(filename) ? "core.prev.log" : filename + ".old";
        return new RotatingWriter(filesDir, filename, prevName, maxBytes);
    }

    public static void writeLine(RotatingWriter log, String line) {
        if (log != null) {
            log.writeLine(line);
        }
    }

    /** Writes an already-redacted line to the buffered core log, flushing on demand or periodically. */
    public static void writeRedactedLine(RotatingWriter log, String safeLine, boolean flushNow) {
        if (log != null) {
            log.writeRedactedLine(safeLine, flushNow);
        }
    }

    public static void closeQuietly(Closeable writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
