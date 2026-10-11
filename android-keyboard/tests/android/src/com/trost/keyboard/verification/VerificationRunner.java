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
            checkContains("fishka typing");checkContains("Тема клавиатуры");checkContains("Неон");checkContains("Аметист");checkContains("Океан");
            screenshot("settings-restored.png");
            click("Тема: Аметист");waitForIdleSync();
            check("violet".equals(context.getSharedPreferences("settings",0).getString("theme","")),"Theme selection did not persist");
            screenshot("theme-selection.png");click("Тема: Неон");waitForIdleSync();
            verifyPressAnimation();
            click("Языки");waitForIdleSync();
            click("Обновить из интернета");
            long downloadDeadline=android.os.SystemClock.uptimeMillis()+15000;
            while(android.os.SystemClock.uptimeMillis()<downloadDeadline&&!contains("Раскладки обновлены"))Thread.sleep(100);
            screenshot("downloaded-layouts.png");
            check(contains("Раскладки обновлены"),"Internet layout refresh failed: "+visibleTexts());
            runOnMainSync(()->{
                android.widget.EditText search=(android.widget.EditText)findType(activity.getWindow().getDecorView(),android.widget.EditText.class);
                check(search!=null,"Language search missing");search.setText("Қазақ");
            });
            waitForIdleSync();
            runOnMainSync(()->{
                android.widget.ListView list=(android.widget.ListView)findType(activity.getWindow().getDecorView(),android.widget.ListView.class);
                check(list!=null&&list.getAdapter().getCount()==1,"Kazakh native layout missing");
                list.performItemClick(list.getChildAt(0),0,list.getAdapter().getItemId(0));
                check("kk".equals(context.getSharedPreferences("settings",0).getString("lang","")),"Kazakh selection did not persist");
            });
            waitForIdleSync();
            click("Ввод");waitForIdleSync();checkContains("Динамический T9");screenshot("t9-restored.png");
            checkAbsent("Отклик");checkAbsent("БЫСТРЕЕ   ·   УДОБНЕЕ   ·   УМНЕЕ");checkContains("Набор текста");
            runOnMainSync(()->{
                try {
                    Class<?> keyboard=Class.forName("com.trost.ioskeyboard.TypingView",true,context.getClassLoader());
                    View view=(View)keyboard.getConstructor(Context.class).newInstance(activity);
                    keyboard.getMethod("setLanguage",String.class).invoke(view,"kk");
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
            waitForIdleSync();screenshot("kazakh-keyboard.png");
            verifyTyping(context);
            result.putString("result","Settings, animated theme selection, original keyboard, rapid native typing, live correction/undo, rapid/held/multi-touch Backspace and browser typing passed");
            result.putString("evidence",evidence.getAbsolutePath());finish(Activity.RESULT_OK,result);
        } catch(Throwable error){result.putString("error",error.toString());finish(Activity.RESULT_CANCELED,result);}
    }
    private void verifyPressAnimation() throws Exception {
        final View[] target=new View[1];
        runOnMainSync(()->{
            target[0]=find(activity.getWindow().getDecorView(),"Тема: Аметист");
            long now=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,20,20,0);
            target[0].dispatchTouchEvent(down);down.recycle();
        });
        Thread.sleep(100);
        runOnMainSync(()->check(target[0].getScaleX()<1f,"Press animation did not run"));
        screenshot("theme-pressed.png");
        runOnMainSync(()->{
            long now=android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent cancel=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_CANCEL,20,20,0);
            target[0].dispatchTouchEvent(cancel);cancel.recycle();
        });
    }
    private View findType(View view,Class<?> kind){
        if(kind.isInstance(view))return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findType(group.getChildAt(i),kind);if(found!=null)return found;}}return null;
    }
    private void verifyTyping(Context context) throws Exception {
        check(context.getSharedPreferences("settings",0).getBoolean("live_correction",false),"Live correction was not enabled by migration");
        check(context.getSharedPreferences("settings",0).getInt("sound_volume",0)==50,"Hidden sound default is not 50 percent");
        context.getSharedPreferences("settings",0).edit().putString("lang","en").putBoolean("autocap",false).putInt("height",60).apply();
        android.app.UiAutomation automation=getUiAutomation();
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        context.startActivity(new Intent().setClassName("com.trost.keyboard.verification","com.trost.keyboard.verification.EditorFixture").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long focusDeadline=android.os.SystemClock.uptimeMillis()+10000;
        android.view.accessibility.AccessibilityNodeInfo nativeField=null;
        while(android.os.SystemClock.uptimeMillis()<focusDeadline){nativeField=findEditable(automation.getRootInActiveWindow());if(nativeField!=null)break;Thread.sleep(100);}
        check(nativeField!=null,"Native editor did not become visible");
        android.graphics.Rect nativeBounds=new android.graphics.Rect();nativeField.getBoundsInScreen(nativeBounds);
        tap(automation,nativeBounds.centerX(),nativeBounds.centerY());
        long deadline=android.os.SystemClock.uptimeMillis()+15000;
        android.graphics.Rect keyboard=null;
        while(android.os.SystemClock.uptimeMillis()<deadline){keyboard=keyboardBounds(automation);if(keyboard!=null)break;Thread.sleep(100);}
        check(keyboard!=null,"IME did not open for native editor");
        captureDevice(automation,"before-native-typing.png");
        android.util.Log.i("FishkaVerification","IME bounds "+keyboard);
        type(automation,keyboard,"hello world hello world ");
        check(keyboardBounds(automation)!=null,"IME hid itself during rapid typing");
        String typed=editorText(automation);
        check(typed.contains("hello world hello world"),"Rapid typing lost characters: "+typed);
        Class<?> lexicon=Class.forName("com.trost.ioskeyboard.Lexicon",true,context.getClassLoader());
        Object engine=lexicon.getField("INSTANCE").get(null);
        long loaded=android.os.SystemClock.uptimeMillis()+12000;
        while(!(Boolean)lexicon.getMethod("contains",String.class,String.class).invoke(engine,"en","world")&&android.os.SystemClock.uptimeMillis()<loaded)Thread.sleep(100);
        check((Boolean)lexicon.getMethod("contains",String.class,String.class).invoke(engine,"en","world"),"English dictionary did not load");
        String proposed=(String)lexicon.getMethod("liveCorrect",String.class,String.class).invoke(engine,"en","wrold");
        check("world".equals(proposed),"Bundled live-correction candidate missing: "+proposed);
        type(automation,keyboard,"wrold");
        long corrected=android.os.SystemClock.uptimeMillis()+3000;
        while(!editorText(automation).endsWith("world")&&android.os.SystemClock.uptimeMillis()<corrected)Thread.sleep(100);
        check(editorText(automation).endsWith("world"),"Live typo correction did not run before space: "+editorText(automation));
        key(automation,keyboard,"back");Thread.sleep(100);
        check(editorText(automation).endsWith("wrold"),"Backspace did not undo live correction");
        check(keyboardBounds(automation)!=null,"Undo hid the IME");
        verifyDeletion(automation,keyboard,engine,lexicon);
        captureDevice(automation,"native-typing.png");
        android.view.accessibility.AccessibilityNodeInfo clear=findNode(automation.getRootInActiveWindow(),"Clear field");
        check(clear!=null&&clear.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"Clear fixture missing");
        Thread.sleep(100);
        type(automation,keyboard,"wrold");
        android.view.accessibility.AccessibilityNodeInfo password=findNode(automation.getRootInActiveWindow(),"Password field");
        check(password!=null&&password.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK),"Password fixture missing");
        Thread.sleep(450);
        android.view.accessibility.AccessibilityNodeInfo protectedField=findEditable(automation.getRootInActiveWindow());
        check(protectedField!=null&&protectedField.isPassword(),"Password session did not activate");
        String privateText=editorText(automation);
        check(privateText.isEmpty()||"Native typing field".equals(privateText),"A stale correction edited the new password session");
        android.view.accessibility.AccessibilityNodeInfo browser=waitForNode(automation,"Browser typing field");
        check(browser!=null,"Browser fixture button missing");
        browser.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);
        long pageLoaded=android.os.SystemClock.uptimeMillis()+10000;
        android.view.accessibility.AccessibilityNodeInfo field=null;
        while(android.os.SystemClock.uptimeMillis()<pageLoaded){field=findEditable(automation.getRootInActiveWindow());if(field!=null)break;Thread.sleep(100);}
        check(field!=null,"Browser editable field missing");
        field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS);
        android.graphics.Rect bounds=new android.graphics.Rect();field.getBoundsInScreen(bounds);tap(automation,bounds.centerX(),bounds.centerY());
        deadline=android.os.SystemClock.uptimeMillis()+8000;keyboard=null;
        while(android.os.SystemClock.uptimeMillis()<deadline){keyboard=keyboardBounds(automation);if(keyboard!=null)break;Thread.sleep(100);}
        check(keyboard!=null,"Browser IME did not open");
        type(automation,keyboard,"hello world hello world");
        check(keyboardBounds(automation)!=null,"IME hid itself while typing in browser");
        check(editorText(automation).contains("hello world hello world"),"Browser typing lost characters");
        captureDevice(automation,"browser-typing.png");
    }
    private void verifyDeletion(android.app.UiAutomation automation,android.graphics.Rect bounds,Object engine,Class<?> lexicon) throws Exception {
        for(String word:new String[]{"hello","help","helicopter"})
            lexicon.getMethod("learn",String.class,String.class,String.class).invoke(engine,"en","say",word);
        android.view.accessibility.AccessibilityNodeInfo field=findEditable(automation.getRootInActiveWindow());
        check(field!=null,"Deletion editor missing");
        Bundle text=new Bundle();text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"");
        check(field.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT,text),"Could not reset deletion field");
        type(automation,bounds,"hel");key(automation,bounds,"back");Thread.sleep(300);
        check("he".equals(editorText(automation)),"Learned-prefix Backspace crashed or corrupted input: "+editorText(automation));
        for(int round=0;round<5;round++){
            type(automation,bounds,"typing");
            for(int i=0;i<6;i++)key(automation,bounds,"back");
        }
        check("he".equals(editorText(automation)),"Rapid repeated deletion lost text");
        multiTouchDelete(automation,bounds);
        check("he".equals(editorText(automation)),"Overlapping letter/Backspace touches corrupted text");
        type(automation,bounds,"abcdefghijklmnopqrstuv");
        int before=editorText(automation).length();
        float density=getTargetContext().getResources().getDisplayMetrics().density;
        float x=bounds.left+bounds.width()*.94f,y=bounds.top+(48+2.5f*60)*density;
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(down,true);down.recycle();
        Thread.sleep(750);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),android.view.MotionEvent.ACTION_UP,x,y,0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);automation.injectInputEvent(up,true);up.recycle();
        check(editorText(automation).length()<before-2,"Backspace hold did not repeat");
        check(keyboardBounds(automation)!=null,"Deletion closed the IME");
        captureDevice(automation,"rapid-backspace.png");
    }
    private void multiTouchDelete(android.app.UiAutomation automation,android.graphics.Rect bounds) throws Exception {
        float density=getTargetContext().getResources().getDisplayMetrics().density,width=bounds.width()-6*density;
        android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[2];
        android.view.MotionEvent.PointerCoords[] points=new android.view.MotionEvent.PointerCoords[2];
        for(int i=0;i<2;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;points[i]=new android.view.MotionEvent.PointerCoords();points[i].pressure=1;points[i].size=1;}
        points[0].x=bounds.left+3*density+(.5f/10f)*width;points[0].y=bounds.top+(48+.5f*60)*density;
        points[1].x=bounds.left+width*.94f;points[1].y=bounds.top+(48+2.5f*60)*density;
        long now=android.os.SystemClock.uptimeMillis();
        int[] actions={android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),android.view.MotionEvent.ACTION_POINTER_UP|(1<<android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT),android.view.MotionEvent.ACTION_UP};
        for(int step=0;step<actions.length;step++){
            int count=step==0||step==3?1:2;
            android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now+step*15,actions[step],count,properties,points,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
            try{check(automation.injectInputEvent(event,true),"Multi-touch deletion injection failed");}finally{event.recycle();}
        }
    }
    private android.graphics.Rect keyboardBounds(android.app.UiAutomation automation) {
        android.graphics.Point viewport=new android.graphics.Point();
        ((android.view.WindowManager)getTargetContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(viewport);
        for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){
            if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD){
                android.graphics.Rect found=findKeyboardView(window.getRoot());
                if(found!=null){found.offset(0,-Math.max(0,found.bottom-viewport.y));return found;}
                android.graphics.Rect bounds=new android.graphics.Rect();window.getBoundsInScreen(bounds);
                int height=Math.round(296*getTargetContext().getResources().getDisplayMetrics().density);
                bounds.bottom=Math.min(bounds.bottom,viewport.y);bounds.top=bounds.bottom-height;return bounds;
            }
        }return null;
    }
    private android.graphics.Rect findKeyboardView(android.view.accessibility.AccessibilityNodeInfo node){
        if(node==null)return null;
        String kind=String.valueOf(node.getClassName());
        if(kind.contains("TypingView")){android.graphics.Rect bounds=new android.graphics.Rect();node.getBoundsInScreen(bounds);return bounds;}
        for(int i=0;i<node.getChildCount();i++){android.graphics.Rect result=findKeyboardView(node.getChild(i));if(result!=null)return result;}return null;
    }
    private void type(android.app.UiAutomation automation,android.graphics.Rect bounds,String value) throws Exception {
        for(int i=0;i<value.length();i++)key(automation,bounds,String.valueOf(value.charAt(i)));
    }
    private void key(android.app.UiAutomation automation,android.graphics.Rect bounds,String value) throws Exception {
        float density=getTargetContext().getResources().getDisplayMetrics().density;
        String[] rows={"qwertyuiop","asdfghjkl","zxcvbnm"};int row=-1,index=-1;
        for(int i=0;i<3;i++){int n=rows[i].indexOf(value);if(n>=0){row=i;index=n;break;}}
        float width=bounds.width()-6*density,x;
        if(" ".equals(value)){row=3;x=(1.25f+1+4.25f/2)/9.15f*width;}
        else if("back".equals(value)){row=2;x=width*.94f;}
        else {check(row>=0,"Unknown test key "+value);x=(row==0?(index+.5f)/10f:row==1?(index+1f)/10f:(index+2.1f)/10.2f)*width;}
        float y=bounds.top+(48+(row+.5f)*60)*density;
        tap(automation,bounds.left+3*density+x,y);
    }
    private void tap(android.app.UiAutomation automation,float x,float y) throws Exception {
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(now,now+12,android.view.MotionEvent.ACTION_UP,x,y,0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{check(automation.injectInputEvent(down,true),"Touch-down failed");check(automation.injectInputEvent(up,true),"Touch-up failed");}finally{down.recycle();up.recycle();}
    }
    private String editorText(android.app.UiAutomation automation){android.view.accessibility.AccessibilityNodeInfo field=findEditable(automation.getRootInActiveWindow());return field==null||field.getText()==null?"":field.getText().toString();}
    private android.view.accessibility.AccessibilityNodeInfo findEditable(android.view.accessibility.AccessibilityNodeInfo node){
        if(node==null)return null;if(node.isEditable())return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo result=findEditable(node.getChild(i));if(result!=null)return result;}return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo waitForNode(android.app.UiAutomation automation,String label) throws InterruptedException {
        long deadline=android.os.SystemClock.uptimeMillis()+5000;
        while(android.os.SystemClock.uptimeMillis()<deadline){
            for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){
                if(window.getType()==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION){
                    android.view.accessibility.AccessibilityNodeInfo node=findNode(window.getRoot(),label);if(node!=null)return node;
                }
            }
            Thread.sleep(100);
        }return null;
    }
    private android.view.accessibility.AccessibilityNodeInfo findNode(android.view.accessibility.AccessibilityNodeInfo node,String label){
        if(node==null)return null;if(label.equalsIgnoreCase(node.getText()==null?"":node.getText().toString()))return node;
        for(int i=0;i<node.getChildCount();i++){android.view.accessibility.AccessibilityNodeInfo result=findNode(node.getChild(i),label);if(result!=null)return result;}return null;
    }
    private void captureDevice(android.app.UiAutomation automation,String name) throws IOException {
        Bitmap bitmap=automation.takeScreenshot();check(bitmap!=null,"Device screenshot failed");
        try(FileOutputStream out=new FileOutputStream(new File(evidence,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private String visibleTexts(){StringBuilder result=new StringBuilder();runOnMainSync(()->appendTexts(activity.getWindow().getDecorView(),result));return result.toString();}
    private void appendTexts(View view,StringBuilder into){if(view instanceof TextView)into.append(((TextView)view).getText()).append(" | ");if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)appendTexts(group.getChildAt(i),into);}}
    private boolean contains(String label){final boolean[] value={false};runOnMainSync(()->value[0]=find(activity.getWindow().getDecorView(),label)!=null);return value[0];}
    private void checkAbsent(String label){runOnMainSync(()->check(find(activity.getWindow().getDecorView(),label)==null,"Unexpected control: "+label));}
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
