// Зачем этот класс: показывает уведомление Sideload Lab.
// Живёт в процессе лаунчера. AlarmManager будит его и после
// убийства процесса :game. Тап открывает LauncherActivity.

package com.artemdev.sideloadlab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class SideloadAlarmReceiver extends BroadcastReceiver {

    public static final String CHANNEL = "sideload_lab";
    private static final int NOTE_ID = 70;

    @Override
    public void onReceive(Context context, Intent intent) {
        show(context);
    }

    public static void show(Context context) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Sideload Lab",
                    NotificationManager.IMPORTANCE_DEFAULT);
            manager.createNotificationChannel(channel);
            builder = new Notification.Builder(context, CHANNEL);
        } else {
            builder = new Notification.Builder(context);
        }

        Intent open = new Intent(context, LauncherActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags = flags | PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent tap = PendingIntent.getActivity(context, NOTE_ID, open, flags);

        builder.setSmallIcon(context.getApplicationInfo().icon);
        builder.setContentTitle("Sideload Lab");
        builder.setContentText("Открой Sideload BIOS");
        builder.setAutoCancel(true);
        builder.setContentIntent(tap);
        manager.notify(NOTE_ID, builder.build());
    }
}
