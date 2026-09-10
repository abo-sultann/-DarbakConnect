package com.abosultan.darbakconnect;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class ConnectService extends Service {
    public static final int PORT = 8765;
    private LocalServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundIfNeeded();
        startServer();
    }

    private void startServer() {
        if (server != null) return;
        try {
            server = new LocalServer(getApplicationContext(), PORT);
            server.start(5000, false);
            DarbakStore.log(this, "system", "تم تشغيل دربك اتصال");
            DarbakRuntime.markHealthy();
        } catch (Exception e) {
            DarbakStore.log(this, "error", "تعذر تشغيل الاتصال المحلي: " + e.getMessage());
            server = null;
        }
    }

    private void startForegroundIfNeeded() {
        if (Build.VERSION.SDK_INT < 26) return;
        String id = "darbak_connect_service";
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            NotificationChannel channel = new NotificationChannel(id, "دربك اتصال", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("الاتصال المحلي بين الهاتف وشاشة السيارة");
            nm.createNotificationChannel(channel);
        }
        Notification notification = new Notification.Builder(this, id)
                .setSmallIcon(com.abosultan.darbakconnect.R.drawable.ic_launcher)
                .setContentTitle("دربك اتصال")
                .setContentText("التحكم المحلي متاح عبر Wi‑Fi")
                .setOngoing(true)
                .build();
        startForeground(1001, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (server == null) startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            server = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
