package com.trost.ioskeyboard;

import android.content.*;
import android.inputmethodservice.InputMethodService;
import android.os.*;
import android.text.InputType;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.*;
import java.util.*;
import kotlin.Unit;

public final class IosKeyboardService extends InputMethodService {
    private static final String TAG="FishkaIME";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Set<String> rejected=new HashSet<>();
    private HandlerThread rankingThread;
    private Handler ranking;
    private SharedPreferences prefs;
    private TypingView keyboard;
    private KeySound sounds;
    private EditorInfo editor;
    private String language="ru",mode="letters";
    private String lastQuery="",undoBefore="",undoAfter="",undoContext="";
    private int undoCursor=-1;
    private boolean active,privateEditor,selectionCollapsed=true,caps,shift,keepChat;
    private int session,request,selectionStart=-1,selectionEnd=-1;
    private long lastShift,lastSpace;
    private Runnable rankTask;
    private final Runnable updateTask=()->updatePredictions();

    @Override public void onCreate() {
        super.onCreate();AppPrefs.ensure(this);prefs=ThemeConfig.prefs(this);sounds=new KeySound(this);
        rankingThread=new HandlerThread("fishka-ranking",android.os.Process.THREAD_PRIORITY_BACKGROUND);
        rankingThread.start();ranking=new Handler(rankingThread.getLooper());
        Lexicon.INSTANCE.load(getApplicationContext());
    }
    @Override public View onCreateInputView() {
        keyboard=new TypingView(this);
        keyboard.setOnKey(key->{press(key);return Unit.INSTANCE;});
        keyboard.setOnFeedback(key->{feedback(key);return Unit.INSTANCE;});
        keyboard.setOnSuggestion(word->{choose(word);return Unit.INSTANCE;});
        keyboard.setOnCursor(steps->{moveCursor(steps);return Unit.INSTANCE;});
        keyboard.setOnLongLanguage(()->{cycleLanguage();return Unit.INSTANCE;});
        configure(true);return keyboard;
    }
    @Override public boolean onEvaluateFullscreenMode(){return false;}
    @Override public void onStartInput(EditorInfo info,boolean restarting) {
        super.onStartInput(info,restarting);cancelPending();session++;editor=info;
        privateEditor=isPrivate(info);selectionStart=info==null?-1:info.initialSelStart;
        selectionEnd=info==null?-1:info.initialSelEnd;selectionCollapsed=selectionStart==selectionEnd;
        language=validLanguage(prefs.getString("lang","ru"));
        int inputClass=info==null?InputType.TYPE_CLASS_TEXT:info.inputType&InputType.TYPE_MASK_CLASS;
        mode=inputClass==InputType.TYPE_CLASS_NUMBER||inputClass==InputType.TYPE_CLASS_PHONE||inputClass==InputType.TYPE_CLASS_DATETIME?"numeric":"letters";
        shift=false;caps=false;keepChat=false;lastSpace=0;lastQuery="";clearUndo();rejected.clear();
        if(keyboard!=null){keyboard.cancelTouches();configure(true);}
    }
    @Override public void onStartInputView(EditorInfo info,boolean restarting) {
        super.onStartInputView(info,restarting);active=true;editor=info;privateEditor=isPrivate(info);
        language=validLanguage(prefs.getString("lang","ru"));
        if(keyboard!=null){
            if(keyboard.getParent()==null)setInputView(keyboard);
            keyboard.cancelTouches();configure(true);updateInitialCaps();
        }
        ClipboardPanel.start(this);schedulePredictions();
    }
    @Override public void onFinishInputView(boolean finishing) {
        active=false;cancelPending();clearUndo();
        if(keyboard!=null)keyboard.cancelTouches();ClipboardPanel.pause(this);
        super.onFinishInputView(finishing);
    }
    @Override public void onFinishInput() {
        active=false;cancelPending();session++;editor=null;privateEditor=true;
        clearUndo();ClipboardPanel.pause(this);super.onFinishInput();
    }
    @Override public void onUpdateSelection(int oldStart,int oldEnd,int newStart,int newEnd,int candidatesStart,int candidatesEnd) {
        super.onUpdateSelection(oldStart,oldEnd,newStart,newEnd,candidatesStart,candidatesEnd);
        boolean moved=selectionStart!=newStart||selectionEnd!=newEnd;
        selectionStart=newStart;selectionEnd=newEnd;selectionCollapsed=newStart==newEnd;
        if(!selectionCollapsed){clearUndo();cancelPending();setSuggestions(Collections.emptyList());}
        else if(active&&moved)schedulePredictions();
    }
    @Override public void onDestroy() {
        active=false;cancelPending();ClipboardPanel.stop(this);if(sounds!=null)sounds.close();
        if(rankingThread!=null)rankingThread.quitSafely();super.onDestroy();
    }
    @Override public boolean onKeyDown(int code,KeyEvent event) {
        if(code==KeyEvent.KEYCODE_BACK&&keyboard!=null&&keyboard.getParent()==null){setInputView(keyboard);schedulePredictions();return true;}
        return super.onKeyDown(code,event);
    }
    private String validLanguage(String code){return LanguageCatalog.all().contains(code)?code:"ru";}
    private void configure(boolean layout) {
        if(keyboard==null)return;
        keyboard.setLanguage(language);keyboard.setMode(mode);keyboard.setPal(ThemeConfig.palette(this));
        keyboard.setRowHeight(getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?44:Math.max(50,Math.min(72,prefs.getInt("height",60))));
        keyboard.setLetterSize(Math.max(22,Math.min(33,prefs.getInt("letter_size",26))));
        keyboard.setPreviews(prefs.getBoolean("preview",true));keyboard.setAction(actionName());
        keyboard.setUpper(shift||caps);keyboard.setLocked(caps);keyboard.setContinuous(keepChat);
        keyboard.setSuggestions(Collections.emptyList());keyboard.refresh(layout);
    }
    private String actionName() {
        if(editor==null)return "enter";
        switch(editor.imeOptions&EditorInfo.IME_MASK_ACTION){
            case EditorInfo.IME_ACTION_SEARCH:return "search";
            case EditorInfo.IME_ACTION_GO:return "go";
            case EditorInfo.IME_ACTION_NEXT:return "next";
            case EditorInfo.IME_ACTION_SEND:return "send";
            case EditorInfo.IME_ACTION_DONE:return "done";
            default:return "enter";
        }
    }
    private void feedback(String key) {
        if(!active)return;
        if(keyboard!=null&&prefs.getBoolean("vibrate",true))keyboard.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if(sounds!=null)sounds.play(key,50);
    }
    private void press(String key) {
        if(!active||key==null||keyboard==null)return;
        switch(key){
            case "settings":startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
            case "clipboard":if(!privateEditor){cancelPending();keyboard.cancelTouches();ClipboardPanel.show(this,keyboard,false);}return;
            case "continuous":if(!privateEditor&&"letters".equals(mode)){keepChat=!keepChat;keyboard.setContinuous(keepChat);keyboard.invalidate();}return;
            case "language":cycleLanguage();return;
            case "shift":
                long now=SystemClock.uptimeMillis();
                if(caps){caps=false;shift=false;}else if(now-lastShift<320){caps=true;shift=true;}else shift=!shift;
                lastShift=now;updateCaps();return;
            case "123":mode="numbers";clearUndo();configure(true);return;
            case "#+=":mode="symbols";configure(true);return;
            case "abc":mode="letters";configure(true);return;
            case "emoji":mode="emoji";configure(true);return;
            case "back":backspace();return;
            case "space":space();return;
            case "enter":enter();return;
            case "gap":return;
            default:
                clearUndo();lastSpace=0;
                String output="letters".equals(mode)&&(shift||caps)?key.toUpperCase(new Locale(language)):key;
                if(commit(output)){if(!caps)shift=false;updateCaps();schedulePredictions();}
        }
    }
    private boolean commit(String value) {
        InputConnection connection=getCurrentInputConnection();if(connection==null)return false;
        try{return connection.commitText(value,1);}catch(RuntimeException error){connectionFailed(error);return false;}
    }
    private void cycleLanguage() {
        cancelPending();clearUndo();language=validLanguage(LanguageCatalog.next(this,language));
        prefs.edit().putString("lang",language).apply();mode="letters";lastQuery="";rejected.clear();
        configure(true);schedulePredictions();
    }
    private void space() {
        if(!active)return;
        ContextWords words=readContext();learn(words,words==null?"":words.prefix);
        clearUndo();long now=SystemClock.uptimeMillis();InputConnection connection=getCurrentInputConnection();if(connection==null)return;
        try {
            CharSequence last=connection.getTextBeforeCursor(2,0);
            if(prefs.getBoolean("double_space",true)&&lastSpace>0&&now-lastSpace<400&&last!=null&&last.length()==2&&last.charAt(1)==' '&&Character.isLetterOrDigit(last.charAt(0))){
                connection.beginBatchEdit();try{connection.deleteSurroundingText(1,0);connection.commitText(". ",1);}finally{connection.endBatchEdit();}
                shift=!privateEditor&&prefs.getBoolean("autocap",true);lastSpace=0;
            }else {connection.commitText(" ",1);lastSpace=now;}
            updateCaps();schedulePredictions();
        }catch(RuntimeException error){connectionFailed(error);}
    }
    private void enter() {
        ContextWords words=readContext();learn(words,words==null?"":words.prefix);clearUndo();
        InputConnection connection=getCurrentInputConnection();if(connection==null)return;
        try {
            int action=editor==null?EditorInfo.IME_ACTION_NONE:editor.imeOptions&EditorInfo.IME_MASK_ACTION;
            boolean explicit=editor!=null&&(editor.imeOptions&EditorInfo.IME_FLAG_NO_ENTER_ACTION)==0&&action!=EditorInfo.IME_ACTION_NONE&&action!=EditorInfo.IME_ACTION_UNSPECIFIED;
            if(explicit)connection.performEditorAction(action);else connection.commitText("\n",1);
            if(keepChat){final int expected=session;final EditorInfo expectedEditor=editor;main.postDelayed(()->{if(keepChat&&!privateEditor&&session==expected&&editor==expectedEditor&&getCurrentInputConnection()!=null)requestShowSelf(0);},80);}
            shift=!privateEditor&&prefs.getBoolean("autocap",true);lastSpace=0;updateCaps();schedulePredictions();
        }catch(RuntimeException error){connectionFailed(error);}
    }
    private void backspace() {
        cancelPending();InputConnection connection=getCurrentInputConnection();if(connection==null)return;
        try {
            if(!undoAfter.isEmpty()){
                CharSequence current=connection.getTextBeforeCursor(undoContext.length(),0);
                if(current!=null&&undoContext.contentEquals(current)&&(undoCursor<0||undoCursor==selectionStart)){
                    String original=undoBefore;rejected.add(original.toLowerCase(Locale.ROOT));
                    if(rejected.size()>32)rejected.clear();
                    connection.beginBatchEdit();try{connection.deleteSurroundingText(undoAfter.length(),0);connection.commitText(original,1);}finally{connection.endBatchEdit();}
                    clearUndo();lastQuery="";schedulePredictions();return;
                }
            }
            clearUndo();lastSpace=0;
            if(!selectionCollapsed)connection.commitText("",1);
            else {
                CharSequence before=connection.getTextBeforeCursor(2,0);
                int count=before==null||before.length()==0?1:Character.charCount(Character.codePointBefore(before,before.length()));
                connection.deleteSurroundingText(count,0);
            }
            schedulePredictions();
        }catch(RuntimeException error){connectionFailed(error);}
    }
    private void choose(String selected) {
        if(privateEditor||selected==null||!selectionCollapsed||!active)return;
        ContextWords words=readContext();if(words==null)return;
        InputConnection connection=getCurrentInputConnection();if(connection==null)return;
        String replacement=matchCase(selected,words.prefix);
        try {
            connection.beginBatchEdit();try{connection.deleteSurroundingText(words.prefix.length(),0);connection.commitText(replacement+" ",1);}finally{connection.endBatchEdit();}
            learn(words,replacement);clearUndo();lastQuery="";if(!caps)shift=false;updateCaps();schedulePredictions();
        }catch(RuntimeException error){connectionFailed(error);}
    }
    private void moveCursor(int steps) {
        InputConnection connection=getCurrentInputConnection();if(connection==null||!active)return;
        clearUndo();cancelPending();int key=steps<0?KeyEvent.KEYCODE_DPAD_LEFT:KeyEvent.KEYCODE_DPAD_RIGHT;
        try{for(int i=0;i<Math.min(12,Math.abs(steps));i++){connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,key));connection.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,key));}}catch(RuntimeException error){connectionFailed(error);}
    }
    private void schedulePredictions() {
        request++;lastQuery="";main.removeCallbacks(updateTask);
        if(active&&!privateEditor&&selectionCollapsed&&"letters".equals(mode)&&prefs.getBoolean("smart",true))main.postDelayed(updateTask,45);
        else setSuggestions(Collections.emptyList());
    }
    private void cancelPending() {
        request++;main.removeCallbacks(updateTask);
        if(rankTask!=null&&ranking!=null)ranking.removeCallbacks(rankTask);
        rankTask=null;lastQuery="";
    }
    private void updatePredictions() {
        if(!active||privateEditor||!selectionCollapsed||keyboard==null)return;
        if(!Lexicon.INSTANCE.isReady()){main.postDelayed(updateTask,100);return;}
        if(keyboard.hasActiveTouches()){main.postDelayed(updateTask,45);return;}
        final ContextWords words=readContext();if(words==null){setSuggestions(Collections.emptyList());return;}
        final String query=language+'\0'+words.before;
        if(query.equals(lastQuery))return;lastQuery=query;
        final int expectedSession=session,expectedRequest=request,expectedSelection=selectionStart;
        final EditorInfo expectedEditor=editor;final String expectedLanguage=language;
        final boolean correct=prefs.getBoolean("live_correction",true)&&!rejected.contains(words.prefix.toLowerCase(Locale.ROOT));
        if(rankTask!=null)ranking.removeCallbacks(rankTask);
        rankTask=()->{
            List<String> suggestions=Lexicon.INSTANCE.suggest(expectedLanguage,words.prefix,words.previous);
            String correction=correct?Lexicon.INSTANCE.liveCorrect(expectedLanguage,words.prefix):null;
            main.postDelayed(()->{
                if(!active||privateEditor||!selectionCollapsed||session!=expectedSession||request!=expectedRequest||editor!=expectedEditor||selectionStart!=expectedSelection||!language.equals(expectedLanguage)||keyboard==null)return;
                ContextWords current=readContext();if(current==null||!words.before.equals(current.before))return;
                if(correction!=null&&!correction.equalsIgnoreCase(words.prefix)&&!keyboard.hasActiveTouches()){
                    applyCorrection(words,correction);
                }else setSuggestions(suggestions);
            },correct?100:0);
        };
        ranking.post(rankTask);
    }
    private void applyCorrection(ContextWords words,String candidate) {
        InputConnection connection=getCurrentInputConnection();if(connection==null)return;
        String replacement=matchCase(candidate,words.prefix);
        try {
            connection.beginBatchEdit();boolean changed;
            try{changed=connection.deleteSurroundingText(words.prefix.length(),0)&&connection.commitText(replacement,1);}finally{connection.endBatchEdit();}
            if(changed){
                undoBefore=words.prefix;undoAfter=replacement;
                String context=words.before.substring(0,words.before.length()-words.prefix.length())+replacement;
                undoContext=context.substring(Math.max(0,context.length()-160));
                undoCursor=selectionStart<0?-1:selectionStart+replacement.length()-words.prefix.length();
                lastQuery="";setSuggestions(Collections.singletonList(replacement));
            }
        }catch(RuntimeException error){connectionFailed(error);}
    }
    private void learn(ContextWords context,String word) {
        if(context==null||privateEditor||word.isEmpty()||!prefs.getBoolean("learning",true))return;
        final String lang=language;
        final boolean explicitlyKept=rejected.contains(word.toLowerCase(Locale.ROOT));
        ranking.post(()->{
            String corrected=explicitlyKept?null:Lexicon.INSTANCE.liveCorrect(lang,word);
            Lexicon.INSTANCE.learn(lang,context.previous,corrected==null?word:corrected);
        });
    }
    private ContextWords readContext() {
        if(privateEditor||!selectionCollapsed)return null;
        InputConnection connection=getCurrentInputConnection();if(connection==null)return null;
        try {
            CharSequence before=connection.getTextBeforeCursor(160,0),after=connection.getTextAfterCursor(1,0);
            if(before==null)return null;
            if(after!=null&&after.length()>0&&isWord(Character.codePointAt(after,0)))return null;
            String text=before.toString();int end=text.length(),start=end;
            int tokenStart=end;
            while(tokenStart>0&&!Character.isWhitespace(text.charAt(tokenStart-1)))tokenStart--;
            String token=text.substring(tokenStart);
            if(token.indexOf('@')>=0||token.indexOf('/')>=0||token.indexOf(':')>=0)return null;
            for(int i=0;i<token.length();i++)if(Character.isDigit(token.charAt(i)))return null;
            while(start>0){int cp=text.codePointBefore(start);if(!isWord(cp))break;start-=Character.charCount(cp);}
            String prefix=text.substring(start),previous="";int previousEnd=start;
            while(previousEnd>0&&!isWord(text.codePointBefore(previousEnd)))previousEnd-=Character.charCount(text.codePointBefore(previousEnd));
            int previousStart=previousEnd;
            while(previousStart>0&&isWord(text.codePointBefore(previousStart)))previousStart-=Character.charCount(text.codePointBefore(previousStart));
            if(previousEnd>previousStart)previous=text.substring(previousStart,previousEnd);
            return new ContextWords(text,prefix,previous);
        }catch(RuntimeException error){connectionFailed(error);return null;}
    }
    private static boolean isWord(int cp){int type=Character.getType(cp);return Character.isLetter(cp)||type==Character.NON_SPACING_MARK||type==Character.COMBINING_SPACING_MARK||cp=='\''||cp==0x2019;}
    private void setSuggestions(List<String> words) {
        if(keyboard==null)return;
        List<String> result=new ArrayList<>(3);for(String word:words){if(result.size()==3)break;result.add(matchCase(word,shift?"A":""));}
        keyboard.setSuggestions(result);keyboard.invalidate();
    }
    private static String matchCase(String word,String typed) {
        if(word.isEmpty()||typed.isEmpty())return word;
        if(typed.length()>1&&typed.equals(typed.toUpperCase(Locale.ROOT)))return word.toUpperCase(Locale.ROOT);
        if(Character.isUpperCase(typed.codePointAt(0))){int length=Character.charCount(word.codePointAt(0));return word.substring(0,length).toUpperCase(Locale.ROOT)+word.substring(length);}
        return word;
    }
    private void updateInitialCaps() {
        if(privateEditor||!prefs.getBoolean("autocap",true)){shift=false;updateCaps();return;}
        InputConnection connection=getCurrentInputConnection();
        try{shift=connection!=null&&connection.getCursorCapsMode(InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)!=0;}catch(RuntimeException ignored){shift=false;}
        updateCaps();
    }
    private void updateCaps(){if(keyboard!=null){keyboard.setUpper(shift||caps);keyboard.setLocked(caps);keyboard.invalidate();}}
    private void clearUndo(){undoBefore="";undoAfter="";undoContext="";undoCursor=-1;}
    private void connectionFailed(RuntimeException error){cancelPending();Log.w(TAG,"Editor connection unavailable",error);}
    private static boolean isPrivate(EditorInfo info) {
        if(info==null)return true;
        if((info.imeOptions&EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)!=0)return true;
        if(info.extras!=null&&info.extras.getBoolean("android.content.extra.IS_SENSITIVE",false))return true;
        int inputClass=info.inputType&InputType.TYPE_MASK_CLASS,variation=info.inputType&InputType.TYPE_MASK_VARIATION;
        if(inputClass==InputType.TYPE_CLASS_NUMBER)return variation==InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        if(inputClass!=InputType.TYPE_CLASS_TEXT)return true;
        return variation==InputType.TYPE_TEXT_VARIATION_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD||variation==InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
    }
    private static final class ContextWords {
        final String before,prefix,previous;
        ContextWords(String before,String prefix,String previous){this.before=before;this.prefix=prefix;this.previous=previous;}
    }
}
