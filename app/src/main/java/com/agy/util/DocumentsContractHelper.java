package com.agy.util;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;

import java.io.File;

/**
 * Resolves Storage Access Framework (SAF) tree URIs to real filesystem paths.
 * Delegates pure path mapping to {@link SafPathResolver} and resolves
 * Downloads-provider opaque IDs via {@code ContentResolver}.
 */
public final class DocumentsContractHelper {

    private static final String TAG = "DocumentsContractHelper";
    private static final String DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents";
    private static final String DOWNLOADS_PUBLIC_URI = "content://downloads/public_downloads/";
    private static final String MEDIA_DOWNLOADS_URI = "content://media/external/downloads";

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
            String trimmed = documentId.trim();
            if (trimmed.isEmpty()) {
                return null;
            }
            String rawPath = SafPathResolver.stripRawPrefix(trimmed);
            if (rawPath != null) {
                return rawPath;
            }

            File baseStorage = Environment.getExternalStorageDirectory();
            String baseStoragePath = baseStorage.getAbsolutePath();
            String authority = treeUri.getAuthority();
            if (DOWNLOADS_AUTHORITY.equals(authority)) {
                if ("downloads".equalsIgnoreCase(trimmed) || "root".equalsIgnoreCase(trimmed)) {
                    return new File(baseStorage, "Download").getAbsolutePath();
                }
                return resolveDownloadsDocument(context, trimmed, baseStorage);
            }

            String resolved = resolveDocumentId(authority, trimmed, baseStoragePath);
            if (resolved != null) {
                // Only verify non-primary /storage/<volume> mounts on the filesystem.
                if (!resolved.startsWith(baseStoragePath) && !new File(resolved).exists()) {
                    return SafPathResolver.firstExisting(
                            SafPathResolver.candidatesForDocumentId(trimmed, baseStorage));
                }
                return resolved;
            }
            return SafPathResolver.firstExisting(
                    SafPathResolver.candidatesForDocumentId(trimmed, baseStorage));
        } catch (Throwable t) {
            Log.w(TAG, "Error resolving tree uri to path: " + treeUri, t);
            return null;
        }
    }

    private static String resolveDownloadsDocument(Context context, String documentId, File baseStorage) {
        String numericId = documentId.startsWith("msd:")
                ? documentId.substring("msd:".length())
                : documentId;

        if (SafPathResolver.isNumericId(numericId)) {
            String dataPath = querySingleString(context,
                    Uri.parse(DOWNLOADS_PUBLIC_URI + numericId),
                    "_data", null, null);
            if (dataPath != null && !dataPath.isEmpty()) {
                File candidate = new File(dataPath);
                if (candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }

            // API 29+ MediaStore Downloads columns queried directly via URI/names for API 28 compile compatibility.
            if (Build.VERSION.SDK_INT >= 29) {
                String mediaPath = resolveViaMediaStore(context, numericId, baseStorage);
                if (mediaPath != null) {
                    return mediaPath;
                }
            }
        }

        String displayName = querySingleString(context,
                DocumentsContract.buildDocumentUri(DOWNLOADS_AUTHORITY, documentId),
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, null, null);
        if (displayName != null && !displayName.isEmpty()) {
            File candidate = SafPathResolver.downloadCandidate(baseStorage, null, displayName);
            if (candidate != null && candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }

        Log.w(TAG, "cannot resolve Downloads document id to a real path: " + documentId);
        return null;
    }

    private static String resolveViaMediaStore(Context context, String numericId, File baseStorage) {
        if (context == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse(MEDIA_DOWNLOADS_URI),
                    new String[]{"relative_path", "_display_name"},
                    "_id=?",
                    new String[]{numericId},
                    null);
            if (cursor != null && cursor.moveToFirst()) {
                String relativePath = cursor.getString(0);
                String displayName = cursor.getString(1);
                File candidate = SafPathResolver.downloadCandidate(baseStorage, relativePath, displayName);
                if (candidate != null && candidate.exists()) {
                    return candidate.getAbsolutePath();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "MediaStore lookup failed for Downloads id " + numericId, t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    private static String querySingleString(Context context, Uri uri, String column,
                                            String selection, String[] selectionArgs) {
        if (context == null || uri == null) {
            return null;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    uri, new String[]{column}, selection, selectionArgs, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(column);
                if (idx >= 0) {
                    return cursor.getString(idx);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "query failed: " + uri, t);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
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
