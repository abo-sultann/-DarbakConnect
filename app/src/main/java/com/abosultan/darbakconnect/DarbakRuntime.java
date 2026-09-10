package com.abosultan.darbakconnect;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.StatFs;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DarbakRuntime {
    private static final String PREFS = "darbak_platform";
    private static final String KEY_UNCLEAN = "unclean_starts";
    private static final String KEY_LAST_START = "last_start";
    private static Context app;
    private static boolean installed;

    private DarbakRuntime() {}

    public static synchronized void install(Context context) {
        if (installed) return;
        app = context.getApplicationContext();
        installed = true;
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int count = Math.min(9, p.getInt(KEY_UNCLEAN, 0) + 1);
        p.edit().putInt(KEY_UNCLEAN, count).putLong(KEY_LAST_START, System.currentTimeMillis()).commit();

        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread thread, Throwable error) {
                try { writeCrash(thread, error); } catch (Exception ignored) {}
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }

    public static void markHealthy() {
        if (app == null) return;
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_UNCLEAN, 0).commit();
    }

    public static boolean safeModeRecommended() {
        return app != null && app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_UNCLEAN, 0) >= 3;
    }

    public static String lastCrash() {
        if (app == null) return null;
        File file = crashFile();
        if (!file.isFile()) return null;
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    public static String healthReport() {
        if (app == null) return "Darbak Platform runtime غير مهيأ";
        ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(memory);
        StatFs fs = new StatFs(app.getFilesDir().getAbsolutePath());
        long freeStorageMb = fs.getAvailableBytes() / (1024L * 1024L);
        long totalRamMb = memory.totalMem / (1024L * 1024L);
        long freeRamMb = memory.availMem / (1024L * 1024L);
        int unclean = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_UNCLEAN, 0);
        return "دربك اتصال — Darbak Platform\n" +
                "الإصدار: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n" +
                "Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n" +
                "الجهاز: " + Build.MANUFACTURER + " " + Build.MODEL + "\n" +
                "RAM: " + freeRamMb + "MB متاح من " + totalRamMb + "MB\n" +
                "التخزين الداخلي المتاح: " + freeStorageMb + "MB\n" +
                "بدايات غير مكتملة: " + unclean + "\n" +
                "آخر Crash: " + (lastCrash() == null ? "لا يوجد" : "مسجل") + "\n" +
                "الهوية: دربك • تصميم وتطوير • أبوسلطان";
    }

    private static File crashFile() {
        File dir = new File(app.getFilesDir(), "darbak");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "last_crash.txt");
    }

    private static void writeCrash(Thread thread, Throwable error) throws Exception {
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        FileWriter writer = new FileWriter(crashFile(), false);
        writer.write("Darbak Connect Crash Report\n");
        writer.write("Time: " + stamp + "\n");
        writer.write("Version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n");
        writer.write("Android: " + Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + "\n");
        writer.write("Device: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        writer.write("Thread: " + (thread == null ? "unknown" : thread.getName()) + "\n\n");
        writer.write(sw.toString());
        writer.flush();
        writer.close();
    }
}
