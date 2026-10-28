package com.kurye.takip;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

import com.getcapacitor.JSObject;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Vardiya boyunca çalışan ön plan servisi: konumu alır ve doğrudan Supabase'e (adk_courier_ping) gönderir.
 * Uygulama ekranı kapansa, arka plana alınsa ya da uygulama kapatılsa da çalışmaya devam eder.
 * Vardiya bitince ya da çıkış yapılınca (stop) kapanır.
 */
public class TrackerService extends Service implements LocationListener {

    static final String PREFS = "kurye_tracker";
    private static final String CHANNEL = "kurye_konum";
    private static final int NOTIF_ID = 4101;
    private static final long SEND_MIN_MS = 15000L;     // en az 15 sn arayla (hareket yoksa)
    private static final float SEND_MIN_M = 10f;        // ya da 10 m hareket
    private static final long HEARTBEAT_MS = 30000L;    // duran kurye de "canlı" görünsün

    private LocationManager lm;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService io;
    private SharedPreferences prefs;
    private Location lastLoc;
    private Location lastSentLoc;
    private long lastSentAt = 0L;
    private long lastGpsAt = 0L;
    private boolean registered = false;

    private final Runnable heartbeat = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (lastLoc != null && now - lastSentAt >= HEARTBEAT_MS) send(lastLoc);
            handler.postDelayed(this, 15000L);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        io = Executors.newSingleThreadExecutor();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            startAsForeground();
        } catch (Exception e) {            // izin yoksa (ör. yeniden başlatma sonrası) servis sessizce kapanır
            prefs.edit().putString("err", "konum izni yok").apply();
            stopSelf();
        }
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Konum paylaşımı", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Vardiya boyunca konumun yönetimle paylaşılır");
            nm.createNotificationChannel(ch);
        }
        Intent open = getPackageManager().getLaunchIntentForPackage(getPackageName());
        PendingIntent pi = open == null ? null
            : PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Kurye konumu paylaşılıyor")
            .setContentText("Vardiyayı bitirince kapanır")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!prefs.getBoolean("active", false) || prefs.getString("token", null) == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        registerLocation();
        handler.removeCallbacks(heartbeat);
        handler.postDelayed(heartbeat, 15000L);
        return START_STICKY;
    }

    private boolean hasPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void registerLocation() {
        if (registered) return;
        if (!hasPermission()) {
            fail("konum izni yok");
            return;
        }
        try {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 5f, this, Looper.getMainLooper());
                registered = true;
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10000L, 10f, this, Looper.getMainLooper());
                registered = true;
            }
        } catch (SecurityException e) {
            fail("konum izni yok");
            return;
        } catch (Exception e) {
            // sağlayıcı yok ya da hata: sonraki başlatmada tekrar denenir
        }
        if (!registered) fail("konum (GPS) kapalı");
    }

    private void fail(String msg) {
        prefs.edit().putString("err", msg).apply();
        JSObject o = new JSObject();
        o.put("ok", false);
        o.put("err", msg);
        NativeTrackerPlugin.emit("ping", o);
    }

    @Override
    public void onLocationChanged(Location l) {
        if (l == null) return;
        long now = System.currentTimeMillis();
        boolean gps = LocationManager.GPS_PROVIDER.equals(l.getProvider());
        if (gps) lastGpsAt = now;
        else if (now - lastGpsAt < 30000L) return;     // GPS varken ağ konumunu kullanma (daha az isabetli)
        lastLoc = l;
        JSObject o = new JSObject();
        o.put("lat", l.getLatitude());
        o.put("lng", l.getLongitude());
        NativeTrackerPlugin.emit("location", o);
        boolean far = lastSentLoc == null || l.distanceTo(lastSentLoc) >= SEND_MIN_M;
        if (far || now - lastSentAt >= SEND_MIN_MS) send(l);
    }

    private void send(final Location l) {
        lastSentAt = System.currentTimeMillis();
        lastSentLoc = l;
        final String url = prefs.getString("url", null);
        final String key = prefs.getString("key", null);
        final String token = prefs.getString("token", null);
        if (url == null || key == null || token == null) return;
        final int battery = battery();
        io.execute(new Runnable() {
            @Override
            public void run() {
                post(url, key, token, l, battery);
            }
        });
    }

    private int battery() {
        try {
            BatteryManager bm = (BatteryManager) getSystemService(Context.BATTERY_SERVICE);
            int v = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            return v > 0 && v <= 100 ? v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private void post(String url, String key, String token, Location l, int battery) {
        HttpURLConnection c = null;
        String err = "";
        boolean ok = false;
        try {
            JSONObject body = new JSONObject();
            body.put("p_token", token);
            body.put("p_lat", l.getLatitude());
            body.put("p_lng", l.getLongitude());
            body.put("p_speed", l.hasSpeed() ? (double) l.getSpeed() : JSONObject.NULL);
            body.put("p_battery", battery >= 0 ? battery : JSONObject.NULL);
            c = (HttpURLConnection) new URL(url + "/rest/v1/rpc/adk_courier_ping").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setDoOutput(true);
            c.setRequestProperty("apikey", key);
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                ok = true;
            } else {
                InputStream es = c.getErrorStream();
                String txt = "";
                if (es != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(es, StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null && sb.length() < 400) sb.append(line);
                    txt = sb.toString();
                }
                err = txt.contains("oturum") ? "oturum geçersiz" : "sunucu hatası (" + code + ")";
                if (txt.contains("oturum")) {
                    prefs.edit().putBoolean("active", false).remove("token").apply();
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            stopSelf();
                        }
                    });
                }
            }
        } catch (Exception e) {
            err = "bağlantı yok";
        } finally {
            if (c != null) c.disconnect();
        }
        SharedPreferences.Editor ed = prefs.edit();
        if (ok) ed.putLong("lastOk", System.currentTimeMillis()).putString("err", "");
        else ed.putString("err", err);
        ed.apply();
        final JSObject o = new JSObject();
        o.put("ok", ok);
        o.put("err", err);
        handler.post(new Runnable() {
            @Override
            public void run() {
                NativeTrackerPlugin.emit("ping", o);
            }
        });
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(heartbeat);
        try {
            lm.removeUpdates(this);
        } catch (Exception ignored) {
        }
        registered = false;
        if (io != null) io.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    public void onProviderEnabled(String provider) {
        registerLocation();
    }

    public void onProviderDisabled(String provider) {
    }
}
