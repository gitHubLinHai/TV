package com.seagull.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 音乐后台保活前台服务：音乐播放页存在期间保持进程活跃（通知低优先级，点击返回主页）。 */
public final class MusicKeepService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notification.Builder b;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel("music", "音乐播放", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, "music");
        } else b = new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        b.setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("搜看")
            .setContentText("正在后台播放音乐")
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
        startForeground(1, b.build());
        return START_STICKY;
    }
}
