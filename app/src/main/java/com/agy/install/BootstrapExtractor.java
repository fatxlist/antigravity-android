package com.agy.install;

import android.content.res.AssetManager;
import android.util.Log;

import com.agy.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;

/** Stages and extracts the pinned bootstrap; fallback extraction must preserve its symlinks. */
public final class BootstrapExtractor {

    private static final String TAG = "BootstrapExtractor";

    private BootstrapExtractor() {
    }

    public static void extract(AssetManager assets, String asset, File prefix) {
        extract(assets, asset, prefix, null, 0);
    }

    public static void extract(AssetManager assets, String asset, File prefix,
                               String expectedSha256, int expectedSymlinkCount) {
        File tmp = null;
        try {
            tmp = File.createTempFile("bootstrap", ".tar", prefix);
            String actual = copyAndHash(assets, asset, tmp, expectedSha256 != null);
            if (expectedSha256 != null && !expectedSha256.equals(actual)) {
                throw new IOException("bootstrap sha256 mismatch: got " + actual
                        + " want " + expectedSha256);
            }
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            throw new IllegalStateException("cannot stage/verify " + asset + ": " + e.getMessage(), e);
        }

        boolean gzip = isGzip(tmp);
        Log.i(TAG, "bootstrap " + asset + " format=" + (gzip ? "gzip" : "plain-tar"));

        try {
            if (TarRunner.runToyboxTar(tmp, prefix, gzip)
                    && new File(prefix, "bin").isDirectory()
                    && countSymlinks(prefix) >= expectedSymlinkCount) {
                verifyRequiredFiles(prefix, expectedSymlinkCount);
                Log.i(TAG, "bootstrap extracted with system tar");
                return;
            }
            Log.w(TAG, "system tar unavailable/incomplete; using path-safe Java fallback");
            int extractedLinks;
            try (InputStream in = new FileInputStream(tmp)) {
                extractedLinks = gzip
                        ? TarExtractor.untarGz(in, prefix)
                        : TarExtractor.untar(in, prefix);
            }
            if (extractedLinks < expectedSymlinkCount) {
                throw new IOException("bootstrap symlink count mismatch: extracted "
                        + extractedLinks + " expected at least " + expectedSymlinkCount);
            }
            verifyRequiredFiles(prefix, expectedSymlinkCount);
            Log.i(TAG, "bootstrap extracted with Java fallback; symlinks=" + extractedLinks);
        } catch (IOException e) {
            throw new IllegalStateException("bootstrap extraction failed: " + e.getMessage(), e);
        } finally {
            IoUtil.deleteQuietly(tmp);
        }
    }

    private static void verifyRequiredFiles(File prefix, int expectedSymlinkCount) throws IOException {
        File bin = new File(prefix, "bin");
        File bash = new File(bin, "bash");
        File sh = new File(bin, "sh");
        if (!bin.isDirectory() || !bash.isFile() || !sh.isFile()) {
            throw new IOException("bootstrap is missing required shell files under " + bin);
        }
        if (expectedSymlinkCount > 0 && !TextFileDetector.isSymlink(sh)) {
            throw new IOException("bootstrap bin/sh symlink was not preserved");
        }
    }

    private static int countSymlinks(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                count++;
            } else if (child.isDirectory()) {
                count += countSymlinks(child);
            }
        }
        return count;
    }

    private static String copyAndHash(AssetManager assets, String assetPath, File dest, boolean computeHash)
            throws Exception {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        MessageDigest digest = computeHash ? MessageDigest.getInstance("SHA-256") : null;
        byte[] buffer = new byte[64 * 1024];
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (digest != null) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        if (digest == null) {
            return null;
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static boolean isGzip(File file) {
        if (file == null || !file.isFile() || file.length() < 2) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 0x1f && b1 == 0x8b;
        } catch (IOException e) {
            Log.w(TAG, "cannot read magic of " + file + ": " + e);
            return false;
        }
    }
}
