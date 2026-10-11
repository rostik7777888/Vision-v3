package com.trost.keyboard.verification;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.*;

public final class EditorFixture extends Activity {
    private EditText editor;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(16,16,16,16);
        TextView title=new TextView(this);title.setText("fishka typing · synthetic typing test");root.addView(title);
        editor=new EditText(this);editor.setHint("Native typing field");editor.setId(100);editor.setSingleLine(false);
        editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        editor.setMinLines(2);root.addView(editor);
        Button clear=new Button(this);clear.setText("Clear field");clear.setOnClickListener(v->{editor.setText("");focus();});root.addView(clear);
        Button browser=new Button(this);browser.setText("Browser typing field");browser.setOnClickListener(v->web());root.addView(browser);
        Button password=new Button(this);password.setText("Password field");password.setOnClickListener(v->{editor.setText("");editor.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);focus();});root.addView(password);
        setContentView(root);focus();
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&editor!=null&&editor.getParent()!=null)focus();}
    private void focus(){editor.requestFocus();editor.postDelayed(()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(editor,InputMethodManager.SHOW_IMPLICIT),300);}
    private void web() {
        WebView web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);
        web.loadDataWithBaseURL("https://monkeytype.test/","<html><meta name='viewport' content='width=device-width,initial-scale=1'><body style='background:#06140e;color:#eef7f0'><h2>Synthetic browser typing test</h2><textarea id='typing' rows='5' autofocus style='width:90%;font-size:24px' autocapitalize='off' autocomplete='off' spellcheck='false'></textarea><script>document.getElementById('typing').focus()</script></body></html>","text/html","UTF-8",null);
        setContentView(web);web.requestFocus();
    }
}
