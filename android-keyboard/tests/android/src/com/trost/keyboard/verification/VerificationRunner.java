package com.trost.keyboard.verification;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;

public final class VerificationRunner extends Instrumentation {
    private Activity activity;
    private File evidence;
    @Override public void onCreate(Bundle args){super.onCreate(args);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        try {
            Context context=getTargetContext();evidence=new File(context.getExternalFilesDir(null),"verification");evidence.mkdirs();
            Intent launch=new Intent().setClassName(context.getPackageName(),"com.trost.ioskeyboard.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);waitForIdleSync();
            checkContains("Тема клавиатуры");checkContains("Неон");checkContains("Аметист");checkContains("Океан");
            screenshot("settings-restored.png");
            click("Тема: Аметист");waitForIdleSync();
            check("violet".equals(context.getSharedPreferences("settings",0).getString("theme","")),"Theme selection did not persist");
            screenshot("theme-selection.png");click("Тема: Неон");waitForIdleSync();
            click("Ввод");waitForIdleSync();checkContains("Динамический T9");checkContains("Обучение на устройстве");screenshot("t9-restored.png");
            click("Отклик");waitForIdleSync();checkContains("Прослушать щелчок");screenshot("sound-settings.png");
            runOnMainSync(()->{
                try {
                    Class<?> keyboard=Class.forName("com.trost.ioskeyboard.TypingView",true,context.getClassLoader());
                    View view=(View)keyboard.getConstructor(Context.class).newInstance(activity);
                    keyboard.getMethod("setRowHeight",int.class).invoke(view,60);
                    keyboard.getMethod("setLetterSize",int.class).invoke(view,26);
                    keyboard.getMethod("setUpper",boolean.class).invoke(view,true);
                    keyboard.getMethod("setSuggestions",java.util.List.class).invoke(view,java.util.Arrays.asList("привет","спасибо","пожалуйста"));
                    Class<?> themes=Class.forName("com.trost.ioskeyboard.ThemeConfig",true,context.getClassLoader());
                    Object palette=themes.getMethod("palette",Context.class).invoke(null,activity);
                    keyboard.getMethod("setPal",palette.getClass()).invoke(view,palette);
                    FrameLayout frame=new FrameLayout(activity);frame.setBackgroundColor(0xff030b08);
                    TextView title=new TextView(activity);title.setText("Синтетический тест клавиатуры");title.setTextColor(0xffedf5f0);title.setTextSize(18);title.setPadding(24,40,24,24);frame.addView(title);
                    frame.addView(view,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));activity.setContentView(frame);
                } catch(ReflectiveOperationException error){throw new RuntimeException(error);}
            });
            waitForIdleSync();screenshot("keyboard-reference.png");
            result.putString("result","Settings, theme selection, T9 tab, sound tab and original keyboard rendering passed");
            result.putString("evidence",evidence.getAbsolutePath());finish(Activity.RESULT_OK,result);
        } catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
    private void click(String label){
        runOnMainSync(()->{View v=find(activity.getWindow().getDecorView(),label);check(v!=null,"Missing control: "+label);check(v.performClick(),"Control not clickable: "+label);});
    }
    private void checkContains(String label){runOnMainSync(()->check(find(activity.getWindow().getDecorView(),label)!=null,"Missing text: "+label));}
    private View find(View v,String label){
        if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;
        if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))return v;
        if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}}return null;
    }
    private void screenshot(String name){
        runOnMainSync(()->{
            View v=activity.getWindow().getDecorView();check(v.getWidth()>0&&v.getHeight()>0,"Activity has not been laid out");
            Bitmap bitmap=Bitmap.createBitmap(v.getWidth(),v.getHeight(),Bitmap.Config.ARGB_8888);v.draw(new Canvas(bitmap));
            try(FileOutputStream output=new FileOutputStream(new File(evidence,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,output);}catch(IOException error){throw new RuntimeException(error);}finally{bitmap.recycle();}
        });
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
