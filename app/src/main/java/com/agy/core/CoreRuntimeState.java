package com.agy.core;

import com.agy.runtime.RuntimeConfig;

import java.util.concurrent.CopyOnWriteArrayList;

/** Cross-component state shared by the service, the bridge and the UI. */
public final class CoreRuntimeState {

    private static final CopyOnWriteArrayList<Runnable> STATE_LISTENERS = new CopyOnWriteArrayList<>();

    private CoreRuntimeState() {
    }

    /** Published once the core answers on its loopback port. */
    public static volatile String serverUrl = null;
    public static volatile int httpsPort = RuntimeConfig.DEFAULT_HTTPS_PORT;
    public static volatile String csrfToken = null;

    /** Fail-closed startup error, surfaced to the UI instead of a hung splash. */
    public static volatile String lastError = null;
    /** True while an unexpected exit is being auto-restarted (transient, not fatal). */
    public static volatile boolean restarting = false;
    /** Bumped on every successful ready; the UI reloads the app when it changes. */
    public static volatile int readyGeneration = 0;
    /** True while CoreServerService is alive (used to guard deferred background work). */
    public static volatile boolean serviceActive = false;

    public static void addStateListener(Runnable listener) {
        if (listener != null) {
            STATE_LISTENERS.addIfAbsent(listener);
        }
    }

    public static void removeStateListener(Runnable listener) {
        if (listener != null) {
            STATE_LISTENERS.remove(listener);
        }
    }

    public static void notifyStateChanged() {
        for (Runnable listener : STATE_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable ignored) {
            }
        }
    }
}
