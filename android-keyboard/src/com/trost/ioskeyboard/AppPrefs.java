package com.trost.ioskeyboard;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    public static void ensure(Context c) {
        SharedPreferences p=ThemeConfig.prefs(c);
        SharedPreferences.Editor e=p.edit();
        if(!p.getBoolean("fishka_v2_migrated",false)){
            e.putBoolean("sound",true);
            e.putInt("sound_volume",50);
            e.putBoolean("live_correction",true);
            e.putBoolean("smart",true);
            e.putBoolean("learning",true);
            e.putBoolean("fishka_v2_migrated",true);
        }
        if(!p.contains("autocorrect_v2"))e.putBoolean("autocorrect_v2",false);
        if(!p.contains("live_correction"))e.putBoolean("live_correction",true);
        if(!p.contains("smart"))e.putBoolean("smart",true);
        if(!p.contains("learning"))e.putBoolean("learning",true);
        if(!p.contains("sound_volume"))e.putInt("sound_volume",50);
        if(!p.contains("sound"))e.putBoolean("sound",true);
        if(!p.contains("height"))e.putInt("height",60);
        if(!p.contains("letter_size"))e.putInt("letter_size",26);
        if(!p.contains("dark"))e.putBoolean("dark",true);
        if(!p.contains("theme"))e.putString("theme","neon");
        e.remove("online_t9").apply();
        SharedPreferences extra=c.getSharedPreferences("trost_extra",Context.MODE_PRIVATE);
        if(!extra.contains("history"))extra.edit().putBoolean("history",true).apply();
    }
}
