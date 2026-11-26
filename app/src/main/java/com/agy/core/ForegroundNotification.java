package com.agy.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;

import com.agy.util.HostActivityResolver;

/** Builds and updates the low-importance ongoing core notification. */
public final class ForegroundNotification {

    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID = "core_channel";

    private ForegroundNotification() {
    }

    public static void start(Service service) {
        NotificationManager manager =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Core", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Notification notification = build(service, "Core running");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            service.startForeground(NOTIFICATION_ID, notification);
        }
    }

    public static void update(Service service, String status) {
        NotificationManager manager =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, build(service, status));
        }
    }

    private static Notification build(Service service, String status) {
        int icon = service.getResources().getIdentifier(
                "ic_notification", "drawable", service.getPackageName());
        if (icon == 0) {
            icon = android.R.drawable.stat_notify_sync;
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(service, CHANNEL_ID)
                : new Notification.Builder(service);
        Intent openIntent = HostActivityResolver.createOpenIntent(service);
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(
                service, NOTIFICATION_ID, openIntent, piFlags);
        return builder
                .setContentTitle("Antigravity")
                .setContentText(status)
                .setSmallIcon(icon)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .build();
    }
}
