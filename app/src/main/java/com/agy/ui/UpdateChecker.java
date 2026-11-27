package com.agy.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.agy.runtime.RuntimeConfig;
import com.agy.util.IoUtil;
import com.agy.util.UriDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Passive GitHub Release update checker targeting {@code fatxlist/antigravity-android}.
 * Runs at most once every 24 hours after UI ready and respects skipped versions.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String RELEASES_API_URL =
            "https://api.github.com/repos/fatxlist/antigravity-android/releases/latest";
    private static final String RELEASES_PAGE_URL =
            "https://github.com/fatxlist/antigravity-android/releases";
    private static final String PREFS_NAME = "agy_update_checker";
    private static final String KEY_LAST_CHECK_MS = "last_check_ms";
    private static final String KEY_SKIPPED_TAG = "skipped_tag";
    private static final long AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 8000;
    private static final AtomicBoolean IN_FLIGHT = new AtomicBoolean(false);

    private UpdateChecker() {
    }

    /** Performs a background GitHub release check at most once per 24 hours. */
    public static void checkSilentlyIfDue(final Activity activity) {
        if (activity == null) {
            return;
        }
        final Context appCtx = activity.getApplicationContext();
        SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long last = prefs.getLong(KEY_LAST_CHECK_MS, 0L);
        if ((now - last) < AUTO_CHECK_INTERVAL_MS) {
            return;
        }
        startCheck(activity, false);
    }

    /** Performs an immediate user-triggered check (showing feedback when already up to date). */
    public static void checkNow(final Activity activity) {
        if (activity == null) {
            return;
        }
        startCheck(activity, true);
    }

    private static void startCheck(final Activity activity, final boolean interactive) {
        if (!IN_FLIGHT.compareAndSet(false, true)) {
            return;
        }
        final Context appCtx = activity.getApplicationContext();
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SharedPreferences prefs = appCtx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                    prefs.edit().putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis()).apply();

                    JSONObject release = fetchLatestRelease();
                    if (release == null || release.optBoolean("draft", false)
                            || release.optBoolean("prerelease", false)) {
                        notifyUpToDateIfInteractive(activity, interactive);
                        return;
                    }
                    final String tag = release.optString("tag_name", "").trim();
                    if (tag.isEmpty()) {
                        notifyUpToDateIfInteractive(activity, interactive);
                        return;
                    }
                    if (!interactive) {
                        String skipped = prefs.getString(KEY_SKIPPED_TAG, "");
                        if (tag.equalsIgnoreCase(skipped)) {
                            return;
                        }
                    }
                    if (compareSemver(tag, RuntimeConfig.APP_VERSION_NAME) <= 0) {
                        notifyUpToDateIfInteractive(activity, interactive);
                        return;
                    }
                    final String url = pickApkAssetUrl(release);
                    final String notes = release.optString("body", "").trim();
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            showUpdateDialog(activity, tag, notes, url);
                        }
                    });
                } catch (Exception e) {
                    Log.d(TAG, "update check skipped: " + e.getMessage());
                    if (interactive) {
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                if (!activity.isFinishing() && !activity.isDestroyed()) {
                                    Toast.makeText(activity, "Could not check for updates", Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                    }
                } finally {
                    IN_FLIGHT.set(false);
                }
            }
        }, "agy-update-check");
        t.setDaemon(true);
        t.start();
    }

    private static void notifyUpToDateIfInteractive(final Activity activity, boolean interactive) {
        if (!interactive) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    Toast.makeText(activity,
                            "Antigravity v" + RuntimeConfig.APP_VERSION_NAME + " is up to date",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API_URL).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setUseCaches(false);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "Antigravity-Android/" + RuntimeConfig.APP_VERSION_NAME);
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] bytes = IoUtil.readAllBytes(in);
                return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
    }

    private static void showUpdateDialog(final Activity activity, final String tag,
                                         String notes, final String downloadUrl) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        String summary = notes;
        if (summary.length() > 360) {
            summary = summary.substring(0, 360).trim() + "…";
        }
        String msg = "Installed: v" + RuntimeConfig.APP_VERSION_NAME + "\nAvailable: " + tag
                + (summary.isEmpty() ? "" : "\n\n" + summary);
        new AlertDialog.Builder(activity)
                .setTitle("Update available (" + tag + ")")
                .setMessage(msg)
                .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        UriDispatcher.open(activity, downloadUrl);
                    }
                })
                .setNeutralButton("Later", null)
                .setNegativeButton("Skip version", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        activity.getApplicationContext()
                                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                .edit()
                                .putString(KEY_SKIPPED_TAG, tag)
                                .apply();
                    }
                })
                .show();
    }

    /** Compares dotted numeric version strings (ignoring leading 'v' and pre-release suffixes). */
    public static int compareSemver(String a, String b) {
        int[] pa = parseVersionParts(a);
        int[] pb = parseVersionParts(b);
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length ? pa[i] : 0;
            int vb = i < pb.length ? pb[i] : 0;
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parseVersionParts(String raw) {
        if (raw == null) {
            return new int[0];
        }
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int dash = s.indexOf('-');
        if (dash >= 0) {
            s = s.substring(0, dash);
        }
        if (s.isEmpty()) {
            return new int[0];
        }
        String[] segs = s.split("\\.");
        int[] out = new int[segs.length];
        for (int i = 0; i < segs.length; i++) {
            String digits = segs[i].replaceAll("[^0-9]", "");
            try {
                out[i] = digits.isEmpty() ? 0 : Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }

    /** Picks the primary {@code .apk} release asset download URL, falling back to {@code html_url}. */
    public static String pickApkAssetUrl(JSONObject releaseJson) {
        if (releaseJson == null) {
            return RELEASES_PAGE_URL;
        }
        JSONArray assets = releaseJson.optJSONArray("assets");
        String fallbackApk = null;
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "").toLowerCase(Locale.US);
                String url = asset.optString("browser_download_url", "").trim();
                if (!url.startsWith("https://") || !name.endsWith(".apk")) {
                    continue;
                }
                if ("antigravity.apk".equals(name)) {
                    return url;
                }
                if (fallbackApk == null) {
                    fallbackApk = url;
                }
            }
        }
        if (fallbackApk != null) {
            return fallbackApk;
        }
        String htmlUrl = releaseJson.optString("html_url", "").trim();
        return htmlUrl.startsWith("https://") ? htmlUrl : RELEASES_PAGE_URL;
    }
}
