package com.trost.ioskeyboard;
import android.content.Context;
import android.view.View;
import java.util.List;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;
public final class TypingView extends View {
    public TypingView(Context c){super(c);}
    public void setOnKey(Function1<? super String,Unit> callback){}
    public void setOnFeedback(Function1<? super String,Unit> callback){}
    public void setOnSuggestion(Function1<? super String,Unit> callback){}
    public void setOnCursor(Function1<? super Integer,Unit> callback){}
    public void setOnLongLanguage(Function0<Unit> callback){}
    public void setLanguage(String language){} public String getLanguage(){return "ru";}
    public void setMode(String mode){} public String getMode(){return "letters";}
    public void setPal(Palette palette){} public void setRowHeight(int n){} public void setLetterSize(int n){}
    public void setPreviews(boolean n){} public void setUpper(boolean n){} public boolean getUpper(){return false;}
    public void setLocked(boolean n){} public void setContinuous(boolean n){} public void setAction(String action){}
    public void setSuggestions(List<String> words){} public void refresh(boolean layout){}
    public boolean hasActiveTouches(){return false;} public void cancelTouches(){}
}
