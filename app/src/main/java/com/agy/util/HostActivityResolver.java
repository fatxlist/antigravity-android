package com.agy.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import com.agy.ui.MainActivity;
import com.agy.ui.launcher.LauncherDarkActivity;
import com.agy.ui.launcher.LauncherLightActivity;

/**
 * Resolves the active or currently enabled launcher host Activity class so
 * notifications and share intents reopen the matching theme entry without
 * spawning a duplicate MainActivity instance.
 */
public final class HostActivityResolver {

    private HostActivityResolver() {
    }

    /** Returns the active host Activity class, or the enabled launcher entry. */
    public static Class<?> resolve(Context context) {
        MainActivity active = MainActivity.activeActivity;
        if (active != null && !active.isFinishing() && !active.isDestroyed()) {
            return active.getClass();
        }
        if (context != null) {
            try {
                PackageManager pm = context.getPackageManager();
                int lightState = pm.getComponentEnabledSetting(
                        new ComponentName(context, LauncherLightActivity.class));
                if (lightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                    return LauncherLightActivity.class;
                }
                int darkState = pm.getComponentEnabledSetting(
                        new ComponentName(context, LauncherDarkActivity.class));
                if (darkState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        && darkState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                    return LauncherDarkActivity.class;
                }
            } catch (Exception ignored) {
            }
        }
        return MainActivity.class;
    }

    /** Builds a reusable launch Intent targeting the resolved host Activity. */
    public static Intent createOpenIntent(Context context) {
        Intent intent = new Intent(context, resolve(context));
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return intent;
    }
}
