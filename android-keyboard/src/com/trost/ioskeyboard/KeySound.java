package com.trost.ioskeyboard;

import android.app.NotificationManager;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import java.util.*;

public final class KeySound {
    private final AudioManager audio;
    private final NotificationManager notifications;
    private final SoundPool pool;
    private final Set<Integer> ready=new HashSet<>();
    private final Map<Integer,Float> pending=new HashMap<>();
    private final int letters,space,delete;
    private boolean closed;
    private long last;
    public KeySound(Context c) {
        audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        pool=new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->loaded(id,status));
        letters=load(c,"key_click"); space=load(c,"space_click"); delete=load(c,"delete_click");
    }
    private int load(Context c,String name) {
        int id=c.getResources().getIdentifier(name,"raw",c.getPackageName());
        return id==0?0:pool.load(c,id,1);
    }
    public static boolean canPlay(int ringer,int volume,int percent) {
        return ringer==AudioManager.RINGER_MODE_NORMAL&&volume>0&&percent>0;
    }
    private boolean audible() {
        if(audio==null||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL)return false;
        if(notifications==null)return true;
        int filter=notifications.getCurrentInterruptionFilter();
        return filter==NotificationManager.INTERRUPTION_FILTER_ALL||filter==NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }
    public synchronized void play(String key,int percent) {
        if(closed||percent<=0||!audible())return;
        if(Arrays.asList("settings","clipboard","continuous","language","shift","emoji","123","abc","#+=").contains(key))return;
        long now=SystemClock.uptimeMillis(); if(last>0&&now-last<12)return; last=now;
        int sample="back".equals(key)?delete:("space".equals(key)||"enter".equals(key))?space:letters;
        if(sample==0)return; float gain=Math.min(100,percent)/100f;
        if(ready.contains(sample))pool.play(sample,gain,gain,1,0,1f); else pending.put(sample,gain);
    }
    private synchronized void loaded(int id,int status) {
        if(closed||status!=0)return; ready.add(id); Float gain=pending.remove(id);
        if(gain!=null&&audible())pool.play(id,gain,gain,1,0,1f);
    }
    public synchronized void close() {
        if(closed)return; closed=true; pending.clear(); ready.clear(); pool.setOnLoadCompleteListener(null); pool.release();
    }
}
