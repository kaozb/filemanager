package com.lumen.files;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

/**
 * 远程管理的前台服务：常驻通知保活。
 * 没有它，应用一切到后台进程就被系统冻结，HTTP 服务随即不可用。
 */
public final class ShareService extends Service {

    private static final String CHANNEL_ID = "http_share";
    private static final int NOTIFY_ID = 1;

    /** 通知栏「停止共享」按钮触发的动作。 */
    static final String ACTION_STOP = "com.lumen.files.share.STOP";

    static void start(Context context) {
        context.startForegroundService(new Intent(context, ShareService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, ShareService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "远程管理",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("远程管理共享运行时的常驻通知");
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            HttpShareServer.stopActive();
            Bg.toast(this, "远程管理已关闭");
            stopSelf();
            return START_NOT_STICKY;
        }
        HttpShareServer server = HttpShareServer.current();
        if (server == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFY_ID, buildNotification(server));
        // 进程被极端情况杀掉后不再自动重启（重启也无法恢复之前的共享状态）
        return START_NOT_STICKY;
    }

    private Notification buildNotification(HttpShareServer server) {
        String address = HttpShareServer.lanAddress();
        String url = "http://" + (address == null ? "<手机IP>" : address) + ":" + server.port();

        Intent open = new Intent(this, LocationsActivity.class);
        PendingIntent openIntent = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Intent stop = new Intent(this, ShareService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopIntent = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_server)
                .setContentTitle("远程管理运行中")
                .setContentText(url)
                .setStyle(new Notification.BigTextStyle().bigText(
                        "目录：" + server.root().getAbsolutePath() + "\n" + url))
                .setOngoing(true)
                .setContentIntent(openIntent)
                .addAction(new Notification.Action.Builder(null, "停止共享", stopIntent).build())
                .build();
    }
}
