package com.agy.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.agy.util.MimeTypes;
import com.agy.util.TrustedCoreOrigin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves our extracted web assets from filesDir/web for loopback requests,
 * bypassing the core for static files. RPC/streaming endpoints are left alone.
 */
public final class LocalAssetServer {

    private static final String TAG = "WebViewHost";
    private static final int MIN_BUF_BYTES = 4 * 1024;
    private static final int MAX_BUF_BYTES = 64 * 1024;

    private static final class WebRoot {
        final File filesDir;
        final File webDir;
        final String canonicalWeb;

        WebRoot(File filesDir, File webDir, String canonicalWeb) {
            this.filesDir = filesDir;
            this.webDir = webDir;
            this.canonicalWeb = canonicalWeb;
        }
    }

    private volatile WebRoot cachedWebRoot;

    private WebRoot resolveWebRoot(Context context) {
        File filesDir = context.getFilesDir();
        WebRoot root = cachedWebRoot;
        if (root != null && filesDir.equals(root.filesDir)) {
            return root;
        }
        try {
            File webDir = new File(filesDir, "web");
            String canonicalWeb = webDir.getCanonicalPath();
            WebRoot resolved = new WebRoot(filesDir, webDir, canonicalWeb);
            if (webDir.isDirectory()) {
                cachedWebRoot = resolved;
            }
            return resolved;
        } catch (Exception e) {
            return null;
        }
    }

    public WebResourceResponse intercept(Context context, Uri uri, String method, int enginePort) {
        if (context == null || uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!TrustedCoreOrigin.matches(uri, enginePort)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        // Do not intercept RPC or streaming API endpoints
        if (path.startsWith("/exa.") || path.startsWith("/jetski.") || path.contains("Service")
                || path.contains("connect-websocket")) {
            return null;
        }
        if (path.contains("..") || path.indexOf('\0') >= 0) {
            return null;
        }
        WebRoot root = resolveWebRoot(context);
        if (root == null) {
            return null;
        }
        String relPath = path.startsWith("/") ? path.substring(1) : path;
        File target = new File(root.webDir, relPath);
        try {
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(root.canonicalWeb + File.separator)
                    && !canonicalTarget.equals(root.canonicalWeb)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        if (target.isFile() && target.canRead()) {
            long len = target.length();
            try {
                String mime = MimeTypes.getMimeType(target.getName());
                String encoding = MimeTypes.isTextMime(mime) ? "UTF-8" : null;
                Map<String, String> headers = new HashMap<>(4);
                headers.put("Access-Control-Allow-Origin", "*");
                String versionParam = uri.getQueryParameter("v");
                // Versioned asset URLs (?v=...) allow Chromium/V8 to persist compiled bytecode cache across restarts.
                headers.put("Cache-Control",
                        (versionParam != null && !versionParam.isEmpty())
                                ? "public, max-age=31536000, immutable"
                                : "no-cache");
                headers.put("Content-Length", String.valueOf(len));
                int bufSize = (int) Math.min(MAX_BUF_BYTES, Math.max(MIN_BUF_BYTES, len));
                InputStream bis = new BufferedInputStream(new FileInputStream(target), bufSize);
                return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
            } catch (Exception e) {
                Log.w(TAG, "intercept failed for " + path, e);
            }
        }
        return null;
    }
}
