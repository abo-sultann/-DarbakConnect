package com.abosultan.darbakconnect;

import android.Manifest;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.iki.elonen.NanoHTTPD;

public class LocalServer extends NanoHTTPD {
    private static final long SESSION_TTL = 24L * 60L * 60L * 1000L;
    private final Context context;
    private final PackageManager pm;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private int failedLogins = 0;
    private long loginLockedUntil = 0L;

    public LocalServer(Context context, int port) {
        super(port);
        this.context = context.getApplicationContext();
        this.pm = this.context.getPackageManager();
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            String uri = session.getUri();
            if (uri == null) uri = "/";

            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return asset("index.html", "text/html; charset=utf-8");
            }
            if ("/api/ping".equals(uri)) return json(ok().put("name", "Darbak Connect").put("version", BuildConfig.VERSION_NAME));
            if ("/api/login".equals(uri) && Method.POST.equals(session.getMethod())) return login(session);

            if (!authorized(session)) return json(Response.Status.UNAUTHORIZED, error("PIN_REQUIRED", "يلزم إدخال رمز PIN"));

            if ("/api/status".equals(uri)) return json(deviceStatus());
            if ("/api/logs".equals(uri)) return json(ok().put("logs", DarbakStore.logs(context)));
            if ("/api/apps".equals(uri)) return json(apps());
            if ("/api/icon".equals(uri)) return icon(session.getParms().get("pkg"));
            if ("/api/files".equals(uri)) return json(files());
            if ("/api/updates".equals(uri)) return json(localUpdates());
            if ("/api/local-pin".equals(uri)) {
                if (!isLocalHost(session)) return json(Response.Status.FORBIDDEN, error("LOCAL_ONLY", "متاح من شاشة السيارة فقط"));
                return json(ok().put("pin", DarbakStore.getPin(context)));
            }

            if (Method.POST.equals(session.getMethod())) {
                if ("/api/apps/launch".equals(uri)) return launchApp(session);
                if ("/api/upload".equals(uri)) return upload(session);
                if ("/api/install".equals(uri)) return installApk(session);
                if ("/api/change-pin".equals(uri)) return changePin(session);
                if ("/api/forget-sessions".equals(uri)) {
                    sessions.clear();
                    DarbakStore.log(context, "security", "تم تسجيل خروج الأجهزة المتصلة");
                    return json(ok().put("message", "تم إنهاء الجلسات"));
                }
            }

            return json(Response.Status.NOT_FOUND, error("NOT_FOUND", "المسار غير موجود"));
        } catch (Exception e) {
            DarbakStore.log(context, "error", "خطأ بالخادم المحلي: " + safe(e.getMessage()));
            return json(Response.Status.INTERNAL_ERROR, error("SERVER_ERROR", "حدث خطأ غير متوقع"));
        }
    }

    private Response login(IHTTPSession session) throws Exception {
        long now = System.currentTimeMillis();
        if (now < loginLockedUntil) {
            long seconds = Math.max(1L, (loginLockedUntil - now) / 1000L);
            return json(Response.Status.TOO_MANY_REQUESTS, error("LOCKED", "محاولات كثيرة. حاول بعد " + seconds + " ثانية"));
        }
        parseBody(session);
        String pin = session.getParms().get("pin");
        if (!DarbakStore.getPin(context).equals(pin)) {
            failedLogins++;
            if (failedLogins >= 5) {
                failedLogins = 0;
                loginLockedUntil = now + 30000L;
            }
            DarbakStore.log(context, "security", "محاولة PIN غير صحيحة");
            return json(Response.Status.UNAUTHORIZED, error("BAD_PIN", "رمز PIN غير صحيح"));
        }
        failedLogins = 0;
        loginLockedUntil = 0L;
        String token = UUID.randomUUID().toString().replace("-", "");
        sessions.put(token, now + SESSION_TTL);
        Response response = json(ok().put("message", "تم الاتصال بنجاح"));
        response.addHeader("Set-Cookie", "darbak_session=" + token + "; Max-Age=86400; Path=/; SameSite=Strict");
        DarbakStore.log(context, "security", "تم اتصال جهاز موثوق");
        return response;
    }

    private boolean authorized(IHTTPSession session) {
        if (isLocalHost(session)) return true;
        cleanupSessions();
        String cookie = session.getHeaders().get("cookie");
        if (cookie == null) return false;
        for (String part : cookie.split(";")) {
            String p = part.trim();
            if (p.startsWith("darbak_session=")) {
                String token = p.substring("darbak_session=".length());
                Long expiry = sessions.get(token);
                return expiry != null && expiry > System.currentTimeMillis();
            }
        }
        return false;
    }

    private boolean isLocalHost(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return host.startsWith("127.0.0.1") || host.startsWith("localhost") || host.startsWith("[::1]");
    }

    private void cleanupSessions() {
        long now = System.currentTimeMillis();
        List<String> dead = new ArrayList<>();
        for (Map.Entry<String, Long> e : sessions.entrySet()) if (e.getValue() <= now) dead.add(e.getKey());
        for (String key : dead) sessions.remove(key);
    }

    private JSONObject deviceStatus() throws Exception {
        JSONObject out = ok();
        out.put("deviceName", Build.MANUFACTURER + " " + Build.MODEL);
        out.put("android", Build.VERSION.RELEASE);
        out.put("sdk", Build.VERSION.SDK_INT);
        out.put("ip", localIp());
        out.put("port", ConnectService.PORT);
        out.put("appVersion", BuildConfig.VERSION_NAME);
        out.put("ram", ramInfo());
        out.put("storage", storageInfo());
        out.put("battery", batteryInfo());
        out.put("uptimeMs", SystemClock.elapsedRealtime());
        out.put("time", System.currentTimeMillis());
        out.put("sessions", sessions.size());
        out.put("publicStorage", canWritePublicStorage());
        return out;
    }

    private JSONObject ramInfo() throws Exception {
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        if (am != null) am.getMemoryInfo(info);
        JSONObject o = new JSONObject();
        o.put("total", info.totalMem);
        o.put("available", info.availMem);
        o.put("used", Math.max(0L, info.totalMem - info.availMem));
        return o;
    }

    private JSONObject storageInfo() throws Exception {
        File root = Environment.getDataDirectory();
        StatFs fs = new StatFs(root.getAbsolutePath());
        long total = fs.getBlockCountLong() * fs.getBlockSizeLong();
        long available = fs.getAvailableBlocksLong() * fs.getBlockSizeLong();
        JSONObject o = new JSONObject();
        o.put("total", total);
        o.put("available", available);
        o.put("used", Math.max(0L, total - available));
        return o;
    }

    private JSONObject batteryInfo() throws Exception {
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int level = -1;
        int status = -1;
        if (battery != null) {
            level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) level = Math.round(level * 100f / scale);
            status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        }
        JSONObject o = new JSONObject();
        o.put("percent", level);
        o.put("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL);
        return o;
    }

    private JSONObject apps() throws Exception {
        List<ApplicationInfo> all = pm.getInstalledApplications(0);
        List<ApplicationInfo> launchable = new ArrayList<>();
        for (ApplicationInfo info : all) {
            if (pm.getLaunchIntentForPackage(info.packageName) != null) launchable.add(info);
        }
        Collections.sort(launchable, new Comparator<ApplicationInfo>() {
            @Override public int compare(ApplicationInfo a, ApplicationInfo b) {
                boolean ad = isDarbak(a), bd = isDarbak(b);
                if (ad != bd) return ad ? -1 : 1;
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
            }
        });
        JSONArray array = new JSONArray();
        for (ApplicationInfo info : launchable) {
            JSONObject item = new JSONObject();
            item.put("label", String.valueOf(info.loadLabel(pm)));
            item.put("package", info.packageName);
            item.put("system", (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
            item.put("darbak", isDarbak(info));
            try {
                PackageInfo pi = pm.getPackageInfo(info.packageName, 0);
                item.put("versionName", pi.versionName == null ? "" : pi.versionName);
                item.put("versionCode", pi.versionCode);
            } catch (Exception ignored) {
                item.put("versionName", "");
                item.put("versionCode", 0);
            }
            array.put(item);
        }
        return ok().put("apps", array);
    }

    private boolean isDarbak(ApplicationInfo info) {
        String pkg = info.packageName.toLowerCase(Locale.US);
        String label = String.valueOf(info.loadLabel(pm)).toLowerCase(Locale.US);
        return pkg.contains("darbak") || pkg.contains("abosultan") || label.contains("درب") || label.contains("لقني") || label.contains("لقّني");
    }

    private Response icon(String pkg) {
        if (pkg == null || pkg.length() > 180) return json(Response.Status.BAD_REQUEST, error("BAD_PACKAGE", "اسم الحزمة غير صالح"));
        try {
            Drawable d = pm.getApplicationIcon(pkg);
            int w = Math.max(1, d.getIntrinsicWidth());
            int h = Math.max(1, d.getIntrinsicHeight());
            int side = Math.min(128, Math.max(64, Math.max(w, h)));
            Bitmap bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            d.setBounds(0, 0, side, side);
            d.draw(canvas);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, bos);
            bitmap.recycle();
            byte[] bytes = bos.toByteArray();
            Response r = newFixedLengthResponse(Response.Status.OK, "image/png", new ByteArrayInputStream(bytes), bytes.length);
            r.addHeader("Cache-Control", "private, max-age=3600");
            return r;
        } catch (Exception e) {
            return json(Response.Status.NOT_FOUND, error("NO_ICON", "تعذر قراءة الأيقونة"));
        }
    }

    private Response launchApp(IHTTPSession session) throws Exception {
        parseBody(session);
        String pkg = session.getParms().get("pkg");
        if (pkg == null || pkg.length() > 180) return json(Response.Status.BAD_REQUEST, error("BAD_PACKAGE", "اسم الحزمة غير صالح"));
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return json(Response.Status.NOT_FOUND, error("NOT_LAUNCHABLE", "التطبيق غير قابل للفتح"));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
        String label = pkg;
        try { label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0))); } catch (Exception ignored) {}
        DarbakStore.log(context, "app", "تم فتح " + label);
        return json(ok().put("message", "تم فتح التطبيق"));
    }

    private Response upload(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String type = session.getParms().get("type");
        if (type == null) type = "other";
        String tempPath = files.get("file");
        String original = session.getParms().get("file");
        if (tempPath == null || original == null) return json(Response.Status.BAD_REQUEST, error("NO_FILE", "لم يتم استلام ملف"));

        original = sanitizeFileName(original);
        File dir = destination(type);
        if (!dir.exists() && !dir.mkdirs()) return json(Response.Status.INTERNAL_ERROR, error("STORAGE", "تعذر إنشاء مجلد الحفظ"));
        File target = uniqueFile(dir, original);
        copy(new File(tempPath), target);

        if ("apk".equalsIgnoreCase(type)) {
            PackageInfo archive = pm.getPackageArchiveInfo(target.getAbsolutePath(), 0);
            if (archive == null) {
                target.delete();
                return json(Response.Status.BAD_REQUEST, error("BAD_APK", "ملف APK غير صالح"));
            }
        }

        DarbakStore.log(context, "file", "تم استلام " + target.getName());
        JSONObject item = fileJson(target, type);
        return json(ok().put("file", item));
    }

    private Response installApk(IHTTPSession session) throws Exception {
        parseBody(session);
        String path = session.getParms().get("path");
        if (path == null) return json(Response.Status.BAD_REQUEST, error("NO_PATH", "المسار غير موجود"));
        File file = new File(path);
        if (!isAllowedFile(file) || !file.exists() || !file.getName().toLowerCase(Locale.US).endsWith(".apk")) {
            return json(Response.Status.BAD_REQUEST, error("BAD_APK", "ملف التحديث غير صالح"));
        }
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
        if (archive == null) return json(Response.Status.BAD_REQUEST, error("BAD_APK", "تعذر قراءة حزمة APK"));

        Uri uri = FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".files", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(intent);
        DarbakStore.log(context, "update", "تم فتح مثبت الحزمة " + file.getName());
        return json(ok().put("message", "تم فتح شاشة التثبيت"));
    }

    private Response changePin(IHTTPSession session) throws Exception {
        parseBody(session);
        String pin = session.getParms().get("pin");
        if (!DarbakStore.setPin(context, pin)) return json(Response.Status.BAD_REQUEST, error("BAD_PIN", "يجب أن يكون PIN من 4 أرقام"));
        sessions.clear();
        return json(ok().put("message", "تم تغيير الرمز. أعد تسجيل الدخول"));
    }

    private JSONObject files() throws Exception {
        JSONArray arr = new JSONArray();
        addFiles(arr, destination("mp3"), "mp3");
        addFiles(arr, destination("video"), "video");
        addFiles(arr, destination("image"), "image");
        addFiles(arr, destination("apk"), "apk");
        List<JSONObject> list = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) list.add(arr.getJSONObject(i));
        Collections.sort(list, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("modified"), a.optLong("modified"));
            }
        });
        JSONArray sorted = new JSONArray();
        for (int i = 0; i < list.size() && i < 80; i++) sorted.put(list.get(i));
        return ok().put("files", sorted);
    }

    private JSONObject localUpdates() throws Exception {
        JSONArray updates = new JSONArray();
        File dir = destination("apk");
        File[] apks = dir.listFiles();
        if (apks != null) {
            for (File apk : apks) {
                if (!apk.isFile() || !apk.getName().toLowerCase(Locale.US).endsWith(".apk")) continue;
                PackageInfo archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), 0);
                if (archive == null || archive.packageName == null) continue;
                JSONObject o = new JSONObject();
                o.put("file", apk.getName());
                o.put("path", apk.getAbsolutePath());
                o.put("package", archive.packageName);
                o.put("versionName", archive.versionName == null ? "" : archive.versionName);
                o.put("versionCode", archive.versionCode);
                int installedCode = -1;
                String installedName = "";
                try {
                    PackageInfo installed = pm.getPackageInfo(archive.packageName, 0);
                    installedCode = installed.versionCode;
                    installedName = installed.versionName == null ? "" : installed.versionName;
                } catch (Exception ignored) {}
                o.put("installedVersionCode", installedCode);
                o.put("installedVersionName", installedName);
                o.put("available", installedCode < 0 || archive.versionCode > installedCode);
                updates.put(o);
            }
        }
        return ok().put("updates", updates);
    }

    private void addFiles(JSONArray arr, File dir, String type) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) if (f.isFile()) arr.put(fileJson(f, type));
    }

    private JSONObject fileJson(File f, String type) throws Exception {
        JSONObject o = new JSONObject();
        o.put("name", f.getName());
        o.put("path", f.getAbsolutePath());
        o.put("size", f.length());
        o.put("modified", f.lastModified());
        o.put("type", type);
        return o;
    }

    private File destination(String type) {
        boolean pub = canWritePublicStorage();
        File base;
        if (pub) {
            if ("mp3".equalsIgnoreCase(type) || "audio".equalsIgnoreCase(type)) base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Darbak");
            else if ("video".equalsIgnoreCase(type) || "kids".equalsIgnoreCase(type)) base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "DarbakKidsTV");
            else if ("image".equalsIgnoreCase(type) || "photo".equalsIgnoreCase(type)) base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Darbak");
            else if ("apk".equalsIgnoreCase(type)) base = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DarbakAPK");
            else base = new File(Environment.getExternalStorageDirectory(), "DarbakConnect");
        } else {
            File ext = context.getExternalFilesDir(null);
            if (ext == null) ext = context.getFilesDir();
            base = new File(ext, "DarbakConnect/" + type);
        }
        if (!base.exists()) base.mkdirs();
        return base;
    }

    private boolean canWritePublicStorage() {
        if (Build.VERSION.SDK_INT <= 28) {
            return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        }
        return false;
    }

    private boolean isAllowedFile(File file) {
        try {
            String canonical = file.getCanonicalPath();
            String apk = destination("apk").getCanonicalPath();
            return canonical.startsWith(apk + File.separator) || canonical.equals(apk);
        } catch (Exception e) {
            return false;
        }
    }

    private static String sanitizeFileName(String name) {
        name = name.replace('\\', '_').replace('/', '_').replace(':', '_');
        name = name.replaceAll("[\\r\\n\\t]", "_").trim();
        if (name.length() > 120) name = name.substring(name.length() - 120);
        if (name.length() == 0) name = "file_" + System.currentTimeMillis();
        return name;
    }

    private static File uniqueFile(File dir, String name) {
        File out = new File(dir, name);
        if (!out.exists()) return out;
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int i = 1;
        do { out = new File(dir, base + " (" + i++ + ")" + ext); } while (out.exists() && i < 1000);
        return out;
    }

    private static void copy(File from, File to) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        try (FileInputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            out.flush();
        }
    }

    private void parseBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
    }

    private Response asset(String name, String mime) {
        try {
            InputStream in = context.getAssets().open(name);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            byte[] data = out.toByteArray();
            Response r = newFixedLengthResponse(Response.Status.OK, mime, new ByteArrayInputStream(data), data.length);
            securityHeaders(r);
            return r;
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found");
        }
    }

    private Response json(JSONObject object) {
        return json(Response.Status.OK, object);
    }

    private Response json(Response.Status status, JSONObject object) {
        Response r = newFixedLengthResponse(status, "application/json; charset=utf-8", object.toString());
        securityHeaders(r);
        return r;
    }

    private void securityHeaders(Response r) {
        r.addHeader("Cache-Control", "no-store");
        r.addHeader("X-Content-Type-Options", "nosniff");
        r.addHeader("X-Frame-Options", "DENY");
        r.addHeader("Referrer-Policy", "no-referrer");
    }

    private static JSONObject ok() {
        try { return new JSONObject().put("ok", true); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static JSONObject error(String code, String message) {
        try { return new JSONObject().put("ok", false).put("code", code).put("message", message); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String localIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                Enumeration<java.net.InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address && address.isSiteLocalAddress()) return address.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "غير متصل";
    }
}
