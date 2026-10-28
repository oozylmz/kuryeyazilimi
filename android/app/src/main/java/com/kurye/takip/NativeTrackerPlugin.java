package com.kurye.takip;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

/**
 * Kurye konumunu uygulama kapalıyken de sunucuya gönderen yerel servisi (TrackerService) yönetir.
 * Sayfadan (kurye.html) çağrılır: ensurePermissions, start, stop, status.
 */
@CapacitorPlugin(
    name = "NativeTracker",
    permissions = {
        @Permission(alias = "location", strings = { Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION }),
        @Permission(alias = "background", strings = { "android.permission.ACCESS_BACKGROUND_LOCATION" }),
        @Permission(alias = "notifications", strings = { "android.permission.POST_NOTIFICATIONS" })
    }
)
public class NativeTrackerPlugin extends Plugin {

    static NativeTrackerPlugin inst;

    @Override
    public void load() {
        inst = this;
    }

    @Override
    protected void handleOnDestroy() {
        if (inst == this) inst = null;
    }

    /** Servisten sayfaya olay gönderir (uygulama açıksa). */
    static void emit(String name, JSObject data) {
        NativeTrackerPlugin p = inst;
        if (p != null) {
            try {
                p.notifyListeners(name, data);
            } catch (Exception ignored) {
            }
        }
    }

    private boolean granted(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    private JSObject permResult() {
        JSObject r = new JSObject();
        r.put("location", granted("location"));
        r.put("background", Build.VERSION.SDK_INT < 29 || granted("background"));
        r.put("notifications", Build.VERSION.SDK_INT < 33 || granted("notifications"));
        return r;
    }

    // Sırayla: konum -> "her zaman" konum -> bildirim (Android bunları tek seferde istemeye izin vermez)
    @PluginMethod
    public void ensurePermissions(PluginCall call) {
        if (!granted("location")) {
            requestPermissionForAlias("location", call, "locCallback");
        } else {
            askBackground(call);
        }
    }

    @PermissionCallback
    private void locCallback(PluginCall call) {
        if (!granted("location")) {
            call.resolve(permResult());
            return;
        }
        askBackground(call);
    }

    private void askBackground(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 29 && !granted("background") && call.getBoolean("background", true)) {
            requestPermissionForAlias("background", call, "bgCallback");
        } else {
            askNotifications(call);
        }
    }

    @PermissionCallback
    private void bgCallback(PluginCall call) {
        askNotifications(call);
    }

    private void askNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && !granted("notifications")) {
            requestPermissionForAlias("notifications", call, "doneCallback");
        } else {
            call.resolve(permResult());
        }
    }

    @PermissionCallback
    private void doneCallback(PluginCall call) {
        call.resolve(permResult());
    }

    @PluginMethod
    public void start(PluginCall call) {
        String url = call.getString("url");
        String key = call.getString("key");
        String token = call.getString("token");
        if (url == null || key == null || token == null) {
            call.reject("url, key ve token gerekli");
            return;
        }
        Context c = getContext();
        SharedPreferences.Editor e = c.getSharedPreferences(TrackerService.PREFS, Context.MODE_PRIVATE).edit();
        e.putString("url", url).putString("key", key).putString("token", token).putBoolean("active", true).putString("err", "");
        e.apply();
        try {
            ContextCompat.startForegroundService(c, new Intent(c, TrackerService.class));
            call.resolve();
        } catch (Exception ex) {
            call.reject("Servis başlatılamadı: " + ex.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        Context c = getContext();
        c.getSharedPreferences(TrackerService.PREFS, Context.MODE_PRIVATE).edit().putBoolean("active", false).remove("token").apply();
        c.stopService(new Intent(c, TrackerService.class));
        call.resolve();
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        try {
            Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            i.setData(android.net.Uri.fromParts("package", getContext().getPackageName(), null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Ayarlar açılamadı");
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        SharedPreferences p = getContext().getSharedPreferences(TrackerService.PREFS, Context.MODE_PRIVATE);
        JSObject r = new JSObject();
        r.put("active", p.getBoolean("active", false));
        r.put("lastOk", p.getLong("lastOk", 0L));
        r.put("err", p.getString("err", ""));
        call.resolve(r);
    }
}
