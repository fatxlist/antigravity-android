package com.agy.util;

import java.io.File;

/**
 * Pure-Java (no Android APIs) helpers for resolving SAF document IDs to
 * filesystem paths. Split out of {@link DocumentsContractHelper} so the
 * path logic can be unit-tested on a plain JVM.
 */
public final class SafPathResolver {

    private SafPathResolver() {
    }

    /** True for a purely numeric document ID, e.g. {@code "359524"}. */
    public static boolean isNumericId(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Strips a {@code "raw:"} prefix and normalizes the path. Returns {@code null}
     * when the ID has no {@code "raw:"} prefix or when the remainder is empty.
     */
    public static String stripRawPrefix(String documentId) {
        if (documentId == null) {
            return null;
        }
        String trimmed = documentId.trim();
        if (!trimmed.startsWith("raw:")) {
            return null;
        }
        String rawPath = trimmed.substring("raw:".length()).trim();
        return rawPath.isEmpty() ? null : new File(rawPath).getPath();
    }

    /** Strips all leading '/' characters (null-safe). */
    public static String stripLeadingSlashes(String s) {
        if (s == null) {
            return null;
        }
        int idx = 0;
        while (idx < s.length() && s.charAt(idx) == '/') {
            idx++;
        }
        return idx == 0 ? s : s.substring(idx);
    }

    private static boolean hasTraversalOrNul(String path) {
        if (path == null) {
            return false;
        }
        if (path.indexOf('\0') >= 0) {
            return true;
        }
        for (String part : path.replace('\\', '/').split("/")) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Joins a MediaStore Downloads row into a candidate path:
     * {@code <baseStorage>/<relativePath>/<displayName>}, defaulting an empty
     * {@code relativePath} to {@code "Download"}. Rejects traversal segments,
     * path separators in {@code displayName}, and NUL bytes.
     */
    public static File downloadCandidate(File baseStorage, String relativePath, String displayName) {
        if (baseStorage == null || displayName == null) {
            return null;
        }
        String cleanName = displayName.trim();
        if (cleanName.isEmpty()
                || cleanName.indexOf('/') >= 0
                || cleanName.indexOf('\\') >= 0
                || cleanName.indexOf('\0') >= 0
                || ".".equals(cleanName)
                || "..".equals(cleanName)) {
            return null;
        }
        String rel = relativePath != null ? stripLeadingSlashes(relativePath.trim()) : "";
        if (hasTraversalOrNul(rel)) {
            return null;
        }
        File parent = rel.isEmpty() ? new File(baseStorage, "Download") : new File(baseStorage, rel);
        return new File(parent, cleanName);
    }

    /**
     * Candidate filesystem locations for a non-Downloads document ID in priority order.
     */
    public static File[] candidatesForDocumentId(String documentId, File baseStorage) {
        if (documentId == null || baseStorage == null) {
            return new File[0];
        }
        String trimmed = documentId.trim();
        if (trimmed.isEmpty()) {
            return new File[0];
        }
        int colonIdx = trimmed.indexOf(':');
        if (colonIdx >= 0) {
            String volume = trimmed.substring(0, colonIdx);
            String subPath = stripLeadingSlashes(trimmed.substring(colonIdx + 1));
            if (hasTraversalOrNul(subPath)) {
                return new File[0];
            }
            if ("primary".equalsIgnoreCase(volume)) {
                return new File[]{subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath)};
            }
            if ("home".equalsIgnoreCase(volume)) {
                File docs = new File(baseStorage, "Documents");
                return new File[]{subPath.isEmpty() ? docs : new File(docs, subPath)};
            }
            if ("msd".equalsIgnoreCase(volume) || volume.isEmpty()) {
                return new File[0];
            }
            File volRoot = new File("/storage/" + volume);
            return new File[]{
                    subPath.isEmpty() ? volRoot : new File(volRoot, subPath),
                    subPath.isEmpty() ? baseStorage : new File(baseStorage, subPath)
            };
        }
        if (hasTraversalOrNul(trimmed)) {
            return new File[0];
        }
        String clean = stripLeadingSlashes(trimmed);
        return clean.isEmpty() ? new File[]{baseStorage} : new File[]{new File(baseStorage, clean)};
    }

    /** Returns the absolute path of the first candidate that exists, or {@code null}. */
    public static String firstExisting(File[] candidates) {
        if (candidates == null) {
            return null;
        }
        for (File candidate : candidates) {
            if (candidate != null && candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return null;
    }
}
