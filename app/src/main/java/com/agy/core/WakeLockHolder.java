package com.agy.core;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

/** Holds a partial wake lock for the core's lifetime. */
public final class WakeLockHolder {

    private static final String TAG = "WakeLockHolder";

    private PowerManager.WakeLock wakeLock;

    public void acquire(Context context) {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "agy::core");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "wakelock failed", e);
        }
    }

    public void release() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
