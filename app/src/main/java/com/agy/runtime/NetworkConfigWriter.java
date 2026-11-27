package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.net.DnsCollector;
import com.agy.util.AtomicFileWriter;

import java.io.File;

/**
 * The core reads "etc//resolv.conf" (cwd = getFilesDir()). Android has no
 * /etc/resolv.conf, so materialize one plus a minimal hosts file. Both are
 * written atomically so the core never sees a half-written resolv.conf.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";
    private static final String HOSTS_CONTENT = "127.0.0.1 localhost\n::1 localhost\n";
    private static volatile String lastResolvContent = null;

    private NetworkConfigWriter() {
    }

    public static synchronized void write(Context appContext) {
        File etc = new File(appContext.getFilesDir(), "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            Log.w(TAG, "cannot create " + etc);
            return;
        }
        StringBuilder resolv = new StringBuilder();
        resolv.append("options timeout:2 attempts:3 rotate\n");
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        String resolvContent = resolv.toString();
        File resolvFile = new File(etc, "resolv.conf");
        File hostsFile = new File(etc, "hosts");
        if (resolvContent.equals(lastResolvContent)
                && resolvFile.isFile()
                && hostsFile.isFile()
                && hostsFile.length() > 0) {
            return;
        }
        AtomicFileWriter.writeText(resolvFile, resolvContent);
        if (!hostsFile.isFile() || hostsFile.length() == 0) {
            AtomicFileWriter.writeText(hostsFile, HOSTS_CONTENT);
        }
        lastResolvContent = resolvContent;
    }
}
