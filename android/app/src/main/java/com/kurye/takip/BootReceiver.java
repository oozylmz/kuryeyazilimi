package com.kurye.takip;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.core.content.ContextCompat;

/** Telefon yeniden başlarsa ya da uygulama güncellenirse, vardiya açıksa konum servisini yeniden başlatır. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences p = context.getSharedPreferences(TrackerService.PREFS, Context.MODE_PRIVATE);
        if (p.getBoolean("active", false) && p.getString("token", null) != null) {
            try {
                ContextCompat.startForegroundService(context, new Intent(context, TrackerService.class));
            } catch (Exception ignored) {
            }
        }
    }
}
