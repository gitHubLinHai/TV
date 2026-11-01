package com.seagull.tv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

/** 音乐后台保活前台服务：音乐播放页存在期间保持进程活跃（通知低优先级，点击返回主页）。
 *  保活是尽力而为：前台化被系统拒绝（如后台启动前台服务限制）时降级停掉，绝不让应用崩溃。 */
public final class MusicKeepService extends Service {
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification.Builder b;
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel("music", "音乐播放", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                if (nm != null) nm.createNotificationChannel(ch); // 个别 ROM 返回 null，跳过建频道不致崩
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
        } catch (Throwable t) {
            // 前台化失败必须立刻自我了断：startForegroundService 有 5 秒内必须前台化的死限，
            // 挂着不 stop 会被系统整进程击杀。音乐照常在播放页播，只是少了保活。
            android.util.Log.w("TVMusic", "keep service foreground denied, degrade to stop", t);
            stopSelf();
            return START_NOT_STICKY;
        }
    }
}
