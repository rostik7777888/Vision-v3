package com.trost.ioskeyboard;

import android.app.NotificationManager;
import android.content.*;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;
import android.os.SystemClock;
import java.util.*;

public final class KeySound {
    private final Context context;
    private boolean muted;
    private final BroadcastReceiver policyReceiver=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent intent){refreshPolicy();}
    };
    private final AudioManager audio;
    private final NotificationManager notifications;
    private final SoundPool pool;
    private final Set<Integer> ready=new HashSet<>();
    private final Map<Integer,Float> pending=new HashMap<>();
    private final int letters,space,delete;
    private boolean closed;
    private long last;
    public KeySound(Context c) {
        context=c.getApplicationContext();
        audio=(AudioManager)c.getSystemService(Context.AUDIO_SERVICE);
        notifications=(NotificationManager)c.getSystemService(Context.NOTIFICATION_SERVICE);
        pool=new SoundPool.Builder().setMaxStreams(6).setAudioAttributes(new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build();
        pool.setOnLoadCompleteListener((p,id,status)->loaded(id,status));
        letters=load(c,"key_click"); space=load(c,"space_click"); delete=load(c,"delete_click");
        IntentFilter changes=new IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION);
        changes.addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED);
        if(android.os.Build.VERSION.SDK_INT>=33)context.registerReceiver(policyReceiver,changes,Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(policyReceiver,changes);
        refreshPolicy();
    }
    private int load(Context c,String name) {
        int id=c.getResources().getIdentifier(name,"raw",c.getPackageName());
        return id==0?0:pool.load(c,id,1);
    }
    public static boolean canPlay(int ringer,int volume,int percent) {
        return ringer==AudioManager.RINGER_MODE_NORMAL&&volume>0&&percent>0;
    }
    private synchronized void refreshPolicy() {
        muted=audio==null||audio.getRingerMode()!=AudioManager.RINGER_MODE_NORMAL;
        if(notifications!=null){
            int filter=notifications.getCurrentInterruptionFilter();
            muted|=filter!=NotificationManager.INTERRUPTION_FILTER_ALL&&filter!=NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
    }
    private boolean audible(){return !muted;}
    public synchronized void play(String key,int percent) {
        if(closed||key==null||key.isEmpty()||percent<=0||!audible())return;
        if(!"back".equals(key)&&!"space".equals(key)&&!"enter".equals(key)&&key.codePointCount(0,key.length())!=1&&key.codePointAt(0)<0x1f000)return;
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
        try{context.unregisterReceiver(policyReceiver);}catch(IllegalArgumentException ignored){}
    }
}
