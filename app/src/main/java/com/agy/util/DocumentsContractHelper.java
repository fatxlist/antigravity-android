package com.agy.util;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;

/**
 * Resolves Storage Access Framework (SAF) tree URIs to real filesystem paths.
 */
public final class DocumentsContractHelper {

    private static final String TAG = "DocumentsContractHelper";

    private DocumentsContractHelper() {
    }

    public static String resolveTreeUriToPath(Context context, Uri treeUri) {
        if (treeUri == null) {
            return null;
        }
        try {
            String documentId = DocumentsContract.getTreeDocumentId(treeUri);
            if (documentId == null) {
                return null;
            }
            String baseStorage = Environment.getExternalStorageDirectory().getAbsolutePath();
            String resolved = resolveDocumentId(treeUri.getAuthority(), documentId, baseStorage);
            if (resolved == null) {
                return null;
            }
            // Only verify non-primary /storage/<volume> mounts on the filesystem.
            if (!resolved.startsWith(baseStorage)) {
                File external = new File(resolved);
                if (!external.exists()) {
                    return null;
                }
            }
            return resolved;
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving tree uri to path: " + treeUri, t);
            return null;
        }
    }

    /**
     * Pure helper mapping SAF authority + document ID to a candidate filesystem path.
     * Returns {@code null} for opaque IDs (such as numeric/msd Downloads IDs) that
     * cannot be resolved cleanly to a directory path.
     */
    public static String resolveDocumentId(String authority, String documentId, String primaryStorageRoot) {
        if (documentId == null || primaryStorageRoot == null || primaryStorageRoot.isEmpty()) {
            return null;
        }
        String trimmed = documentId.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String baseStorage = new File(primaryStorageRoot).getPath();

        if (trimmed.startsWith("raw:")) {
            String rawPath = trimmed.substring("raw:".length()).trim();
            return rawPath.isEmpty() ? null : new File(rawPath).getPath();
        }

        if ("com.android.providers.downloads.documents".equals(authority)) {
            if ("downloads".equalsIgnoreCase(trimmed) || "root".equalsIgnoreCase(trimmed)) {
                return new File(baseStorage, "Download").getPath();
            }
            if (trimmed.startsWith("msd:") || trimmed.matches("\\d+")) {
                return null;
            }
        }

        int colonIdx = trimmed.indexOf(':');
        if (colonIdx >= 0) {
            String volume = trimmed.substring(0, colonIdx);
            String subPath = trimmed.substring(colonIdx + 1);
            while (subPath.startsWith("/")) {
                subPath = subPath.substring(1);
            }
            if ("primary".equalsIgnoreCase(volume)) {
                return subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath).getPath();
            }
            if ("home".equalsIgnoreCase(volume)) {
                File docs = new File(baseStorage, "Documents");
                return subPath.isEmpty() ? docs.getPath() : new File(docs, subPath).getPath();
            }
            if ("msd".equalsIgnoreCase(volume)) {
                return null;
            }
            if (!volume.isEmpty()) {
                File volRoot = new File("/storage/" + volume);
                return subPath.isEmpty() ? volRoot.getPath() : new File(volRoot, subPath).getPath();
            }
        }

        if (trimmed.startsWith("/")) {
            return new File(trimmed).getPath();
        }
        return new File(baseStorage, trimmed).getPath();
    }
}
