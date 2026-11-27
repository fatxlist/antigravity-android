package com.agy.runtime;

import android.content.Context;

import com.agy.net.NetworkMonitor;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns the single network monitor for the process with debounced async DNS sync. */
public final class NetworkMonitorHolder {

    private static final long DNS_WRITE_DEBOUNCE_MS = 500L;

    private final Context appContext;
    private final ScheduledExecutorService dnsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AgyDnsSync");
        t.setDaemon(true);
        return t;
    });
    private NetworkMonitor monitor;
    private ScheduledFuture<?> pendingWrite;

    public NetworkMonitorHolder(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Registers the default-network callback once; refreshes DNS on changes. */
    public synchronized void start() {
        if (monitor == null) {
            monitor = new NetworkMonitor(appContext, new NetworkMonitor.Listener() {
                @Override
                public void onNetworkChanged() {
                    scheduleDnsWrite();
                }
            });
        }
        monitor.register();
    }

    private synchronized void scheduleDnsWrite() {
        if (pendingWrite != null) {
            pendingWrite.cancel(false);
        }
        pendingWrite = dnsExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                NetworkConfigWriter.write(appContext);
            }
        }, DNS_WRITE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** Safe to call on teardown, repeatedly. */
    public synchronized void unregister() {
        if (pendingWrite != null) {
            pendingWrite.cancel(false);
            pendingWrite = null;
        }
        if (monitor != null) {
            monitor.unregister();
        }
    }
}
