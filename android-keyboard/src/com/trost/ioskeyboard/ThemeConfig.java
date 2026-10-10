package com.trost.ioskeyboard;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

public final class ThemeConfig {
    public final int bg, key, fn, text, accent, muted, border;
    private ThemeConfig(int bg,int key,int fn,int accent,int border) {
        this.bg=bg; this.key=key; this.fn=fn; this.accent=accent; this.border=border;
        text=0xffedf7f0; muted=0xff8eaa9c;
    }
    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings",Context.MODE_PRIVATE);
    }
    public static ThemeConfig from(Context c) { return named(prefs(c).getString("theme","neon")); }
    public static ThemeConfig named(String name) {
        switch(name) {
            case "violet":return new ThemeConfig(0xff090710,0xff211b30,0xff2b2240,0xffbd8eff,0xff443656);
            case "ocean":return new ThemeConfig(0xff040c12,0xff102838,0xff173747,0xff56d9fa,0xff234655);
            case "amber":return new ThemeConfig(0xff100b05,0xff302412,0xff403019,0xffffc66c,0xff594022);
            case "graphite":return new ThemeConfig(0xff090b0d,0xff262c34,0xff353e49,0xffb6c8de,0xff46515f);
            default:return new ThemeConfig(0xff081a13,0xff234235,0xff173429,0xff96e5b2,0xff2c4d3b);
        }
    }
    public static Palette palette(Context c) {
        ThemeConfig t=from(c);
        return new Palette(t.bg,t.key,t.fn,t.text,t.accent,blend(t.key,t.accent,.2f),0x44000000,t.bg,t.muted,t.bg);
    }
    public static Palette select(Context c,Palette ignored) { return palette(c); }
    private static int blend(int a,int b,float n) {
        return Color.rgb((int)(Color.red(a)*(1-n)+Color.red(b)*n),(int)(Color.green(a)*(1-n)+Color.green(b)*n),(int)(Color.blue(a)*(1-n)+Color.blue(b)*n));
    }
}
