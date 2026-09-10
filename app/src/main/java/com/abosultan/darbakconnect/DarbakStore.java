package com.abosultan.darbakconnect;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.SecureRandom;

public final class DarbakStore {
    private static final String PREFS = "darbak_connect";
    private static final String KEY_PIN = "pin";
    private static final String KEY_LOGS = "logs";
    private static final Object LOCK = new Object();

    private DarbakStore() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String getPin(Context c) {
        SharedPreferences p = prefs(c);
        String pin = p.getString(KEY_PIN, null);
        if (pin == null || pin.length() != 4) {
            pin = String.format(java.util.Locale.US, "%04d", new SecureRandom().nextInt(10000));
            p.edit().putString(KEY_PIN, pin).apply();
        }
        return pin;
    }

    public static boolean setPin(Context c, String pin) {
        if (pin == null || !pin.matches("\\d{4}")) return false;
        prefs(c).edit().putString(KEY_PIN, pin).apply();
        log(c, "security", "تم تغيير رمز PIN");
        return true;
    }

    public static void log(Context c, String type, String message) {
        synchronized (LOCK) {
            try {
                JSONArray old = new JSONArray(prefs(c).getString(KEY_LOGS, "[]"));
                JSONArray next = new JSONArray();
                JSONObject item = new JSONObject();
                item.put("type", type);
                item.put("message", message);
                item.put("time", System.currentTimeMillis());
                next.put(item);
                for (int i = 0; i < old.length() && next.length() < 60; i++) next.put(old.get(i));
                prefs(c).edit().putString(KEY_LOGS, next.toString()).apply();
            } catch (Exception ignored) {}
        }
    }

    public static JSONArray logs(Context c) {
        synchronized (LOCK) {
            try { return new JSONArray(prefs(c).getString(KEY_LOGS, "[]")); }
            catch (Exception e) { return new JSONArray(); }
        }
    }
}
