package com.trost.ioskeyboard;

import android.app.Activity;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.*;
import android.view.*;
import android.view.inputmethod.*;
import android.widget.*;
import java.util.*;

public final class MainActivity extends Activity {
    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout body;
    private ScrollView scroll;
    private TextView status,enable;
    private KeySound sounds;
    private int tab;
    private boolean languagesOpen;
    private int dp(float n){return Neon.dp(this,n);}
    private TextView text(String s,int size,int color,boolean bold){return Neon.text(this,s,size,color,bold);}
    private LinearLayout col(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(this);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private void add(LinearLayout into,View v,int gap){LinearLayout.LayoutParams p=lp(-1,-2);p.topMargin=dp(gap);into.addView(v,p);}
    @Override public void onCreate(Bundle state){super.onCreate(state);AppPrefs.ensure(this);prefs=ThemeConfig.prefs(this);if(state!=null)tab=state.getInt("tab");show(0);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putInt("tab",tab);}
    @Override public void onResume(){super.onResume();updateStatus();}
    @Override public void onDestroy(){if(sounds!=null)sounds.close();super.onDestroy();}
    private boolean enabled(){for(InputMethodInfo i:((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).getEnabledInputMethodList())if(getPackageName().equals(i.getPackageName()))return true;return false;}
    private void updateStatus(){
        if(status==null)return;boolean on=enabled();status.setText(on?"●  Включена":"○  Не включена");
        status.setTextColor(on?Neon.ACCENT:Neon.MUTED);
        if(enable!=null){String title=on?"Настроить":"Включить";enable.setText(title);((View)enable.getParent()).setContentDescription(title);}
    }
    private LinearLayout card(){LinearLayout l=col();l.setPadding(dp(13),dp(14),dp(13),dp(13));l.setBackground(Neon.shape(this,Neon.SURFACE,Neon.BORDER,22));return l;}
    private LinearLayout tile(){LinearLayout l=col();l.setPadding(dp(12),dp(14),dp(12),dp(14));l.setBackground(Neon.shape(this,Neon.TILE,Neon.BORDER,16));return l;}
    private void show(int y){
        languagesOpen=false;status=null;enable=null;
        getWindow().setStatusBarColor(Neon.BG);getWindow().setNavigationBarColor(Neon.BG);getWindow().getDecorView().setSystemUiVisibility(0);
        root=new FrameLayout(this);root.addView(new Neon.Wallpaper(this),new FrameLayout.LayoutParams(-1,-1));
        scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setVerticalScrollBarEnabled(false);
        body=col();body.setPadding(dp(18),dp(24),dp(18),dp(22));scroll.addView(body);root.addView(scroll);setContentView(root);
        header();add(body,actions(),22);add(body,tabs(),22);
        if(tab==0)appearance();else if(tab==1)input();else feedback();
        add(body,telegram(),14);
        TextView footer=text("БЫСТРЕЕ   ·   УДОБНЕЕ   ·   УМНЕЕ",9,Neon.MUTED,false);footer.setLetterSpacing(.16f);footer.setGravity(Gravity.CENTER);add(body,footer,30);
        scroll.post(()->scroll.scrollTo(0,y));updateStatus();
    }
    private void header(){
        LinearLayout r=row(),brand=col();TextView logo=text("trost",39,Neon.TEXT,true);
        logo.setTypeface(Typeface.create("sans-serif-black",0));logo.setLetterSpacing(-.045f);
        SpannableString name=new SpannableString("trost");name.setSpan(new android.text.style.ForegroundColorSpan(Neon.ACCENT),2,5,0);logo.setText(name);brand.addView(logo);
        brand.addView(text("Умная клавиатура",13,Neon.MUTED,false));r.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        status=text("",11,Neon.ACCENT,true);status.setPadding(dp(10),dp(9),dp(10),dp(9));status.setBackground(Neon.shape(this,Neon.SURFACE,Neon.BORDER,20));r.addView(status);body.addView(r);
    }
    private LinearLayout action(String icon,String title,boolean active,Runnable run){
        LinearLayout r=row();r.setGravity(Gravity.CENTER);r.setPadding(dp(5),dp(14),dp(5),dp(14));
        r.setBackground(active?Neon.gradientButton(this):Neon.button(this,Neon.SURFACE,Neon.BORDER,22));
        r.addView(new Neon.Icon(this,icon,active?Neon.BG:Neon.TEXT),lp(21,21));
        TextView label=text(title,16,active?Neon.BG:Neon.TEXT,true);label.setPadding(dp(10),0,0,0);r.addView(label);
        r.setContentDescription(title);r.setOnClickListener(v->run.run());return r;
    }
    private View actions(){
        LinearLayout r=row(),first=action("gear",enabled()?"Настроить":"Включить",true,()->startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        enable=(TextView)first.getChildAt(1);r.addView(first,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.leftMargin=dp(8);
        r.addView(action("sliders","Выбрать",false,()->((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showInputMethodPicker()),p);return r;
    }
    private View tabs(){
        LinearLayout r=row();r.setBackground(Neon.shape(this,Neon.SURFACE,Neon.BORDER,19));
        String[] labels={"Вид","Ввод","Отклик"},icons={"keyboard","sliders","palette"};
        for(int i=0;i<3;i++){
            final int n=i;LinearLayout cell=col(),inner=row();inner.setGravity(Gravity.CENTER);inner.setPadding(0,dp(13),0,dp(12));
            inner.addView(new Neon.Icon(this,icons[i],i==tab?Neon.ACCENT:Neon.MUTED),lp(20,20));
            TextView label=text(labels[i],13,i==tab?Neon.ACCENT:Neon.MUTED,i==tab);label.setPadding(dp(7),0,0,0);inner.addView(label);cell.addView(inner);
            View line=new View(this);line.setBackground(Neon.shape(this,i==tab?Neon.ACCENT:0,0,2));cell.addView(line,lp(-1,3));
            cell.setContentDescription(labels[i]);cell.setOnClickListener(v->{tab=n;show(0);});r.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
        }
        return r;
    }
    private LinearLayout heading(String icon,String title,String subtitle){
        LinearLayout r=row();FrameLayout badge=new FrameLayout(this);badge.setBackground(Neon.shape(this,0xff0d4028,Neon.BORDER,14));
        badge.addView(new Neon.Icon(this,icon,Neon.ACCENT),new FrameLayout.LayoutParams(dp(26),dp(26),Gravity.CENTER));r.addView(badge,lp(46,46));
        LinearLayout labels=col();labels.setPadding(dp(13),0,0,0);labels.addView(text(title,18,Neon.TEXT,true));add(labels,text(subtitle,11,Neon.MUTED,false),4);
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));return r;
    }
    private void toggle(LinearLayout into,String title,String description,String key,boolean fallback){
        LinearLayout box=tile(),r=row(),labels=col();labels.addView(text(title,15,Neon.TEXT,true));
        if(!description.isEmpty())add(labels,text(description,11,Neon.MUTED,false),4);
        r.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        Neon.Toggle control=new Neon.Toggle(this,prefs.getBoolean(key,fallback),title,on->prefs.edit().putBoolean(key,on).apply());
        LinearLayout.LayoutParams p=lp(48,48);p.leftMargin=dp(8);r.addView(control,p);box.addView(r);add(into,box,9);
    }
    private void segmented(LinearLayout into,String title,String key,String[] labels,int[] values,int fallback){
        LinearLayout box=tile();box.addView(text(title,15,Neon.TEXT,true));LinearLayout r=row();int selected=prefs.getInt(key,fallback);
        for(int i=0;i<labels.length;i++){
            final int value=values[i];TextView v=text(labels[i],12,value==selected?Neon.BG:Neon.MUTED,value==selected);v.setGravity(Gravity.CENTER);v.setPadding(dp(2),dp(11),dp(2),dp(11));
            v.setBackground(Neon.button(this,value==selected?Neon.ACCENT:Neon.BG,value==selected?Neon.ACCENT:Neon.BORDER,12));
            v.setContentDescription(title+": "+labels[i]);v.setOnClickListener(w->{prefs.edit().putInt(key,value).apply();show(scroll.getScrollY());});
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);if(i>0)p.leftMargin=dp(5);r.addView(v,p);
        }
        add(box,r,10);add(into,box,9);
    }
    private void appearance(){
        LinearLayout c=card();c.addView(heading("keyboard","Клавиатура","Внешний вид и стиль клавиш"));
        LinearLayout themes=tile();themes.addView(text("Тема клавиатуры",15,Neon.TEXT,true));HorizontalScrollView hs=new HorizontalScrollView(this);hs.setHorizontalScrollBarEnabled(false);LinearLayout themeRow=row();
        String[] ids={"neon","violet","ocean","amber","graphite"},names={"Неон","Аметист","Океан","Янтарь","Графит"};
        for(int i=0;i<ids.length;i++){
            final String id=ids[i];ThemeConfig t=ThemeConfig.named(id);boolean selected=id.equals(prefs.getString("theme","neon"));
            LinearLayout item=col();item.setPadding(dp(5),dp(5),dp(5),dp(6));item.setBackground(Neon.button(this,t.bg,selected?Neon.ACCENT:Neon.BORDER,13));
            item.addView(new Neon.Preview(this,t),lp(93,52));TextView label=text(names[i],11,selected?Neon.ACCENT:Neon.MUTED,selected);label.setGravity(Gravity.CENTER);add(item,label,4);
            LinearLayout.LayoutParams p=lp(103,-2);if(i>0)p.leftMargin=dp(7);themeRow.addView(item,p);
            item.setContentDescription("Тема: "+names[i]);item.setOnClickListener(v->{prefs.edit().putString("theme",id).apply();show(scroll.getScrollY());});
        }
        hs.addView(themeRow);add(themes,hs,10);add(c,themes,12);
        segmented(c,"Высота клавиш","height",new String[]{"Низкая","Обычная","Высокая"},new int[]{52,60,68},60);
        segmented(c,"Размер букв","letter_size",new String[]{"Меньше","Средний","Больше"},new int[]{22,26,30},26);
        toggle(c,"Подсказка над клавишей","Показывать букву при нажатии","preview",true);add(body,c,18);
        LinearLayout languages=card(),r=heading("globe","Языки","Выбор языка и раскладки");r.addView(new Neon.Icon(this,"arrow",Neon.MUTED),lp(20,20));languages.addView(r);languages.setContentDescription("Языки");languages.setOnClickListener(v->showLanguages());add(body,languages,13);
    }
    private void input(){
        LinearLayout t9=card();t9.addView(heading("sliders","Динамический T9","Подсказки, которые подстраиваются под вас"));
        toggle(t9,"Подсказки слов","Продолжения слов и варианты опечаток","smart",true);
        toggle(t9,"Обучение на устройстве","Запоминает ваши слова и сочетания локально","learning",true);
        LinearLayout note=tile();note.addView(text("Без отправки текста",14,Neon.ACCENT,true));
        add(note,text("Русский и английский словари + ваши слова. Пароли и приватные поля не используются для обучения. Выберите подсказку, чтобы вставить её.",12,Neon.MUTED,false),6);add(t9,note,9);add(body,t9,18);
        LinearLayout typing=card();typing.addView(heading("keyboard","Набор текста","Меньше действий — больше удобства"));
        toggle(typing,"Заглавная в начале","После точки и в новом предложении","autocap",true);
        toggle(typing,"Точка двойным пробелом","Два пробела заменяются на «. »","double_space",true);add(body,typing,13);
        LinearLayout history=card();history.addView(heading("keyboard","История буфера","Разные копии сохраняются, а не заменяются"));
        add(history,text("Откройте буфер на панели клавиатуры. История хранится локально с шифрованием. Очистка не блокирует набор текста.",12,Neon.MUTED,false),12);add(body,history,13);
    }
    private void feedback(){
        LinearLayout c=card();c.addView(heading("sound","Отклик клавиш","Звук из вашей записи и вибрация"));
        toggle(c,"Звук щелчка","Использует реальные щелчки из приложенного MP3","sound",true);
        LinearLayout volume=tile(),r=row();r.addView(text("Громкость щелчка",15,Neon.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        TextView label=text(prefs.getInt("sound_volume",80)+"%",13,Neon.ACCENT,true);r.addView(label);volume.addView(r);
        SeekBar seek=new SeekBar(this);seek.setMax(100);seek.setProgress(prefs.getInt("sound_volume",80));seek.setProgressTintList(ColorStateList.valueOf(Neon.ACCENT));seek.setThumbTintList(ColorStateList.valueOf(Neon.ACCENT));seek.setProgressBackgroundTintList(ColorStateList.valueOf(Neon.BORDER));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar b,int n,boolean user){label.setText(n+"%");if(user)prefs.edit().putInt("sound_volume",n).apply();}
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){previewSound();}
        });
        add(volume,seek,12);add(c,volume,9);add(c,action("sound","Прослушать щелчок",false,this::previewSound),9);
        toggle(c,"Вибрация","Мягкий отклик при нажатии","vibrate",false);add(body,c,18);
        LinearLayout note=card();note.addView(text("Уважает тихий режим",14,Neon.ACCENT,true));
        add(note,text("Щелчок использует громкость мультимедиа. В беззвучном режиме и «Не беспокоить» звук не включается принудительно.",12,Neon.MUTED,false),7);add(body,note,13);
    }
    private void previewSound(){if(sounds==null)sounds=new KeySound(this);sounds.play("a",prefs.getInt("sound_volume",80));}
    private View telegram(){
        LinearLayout c=card(),r=heading("telegram","trost в Telegram","Новости, обновления и общение");r.addView(new Neon.Icon(this,"arrow",Neon.MUTED),lp(20,20));c.addView(r);c.setContentDescription("Открыть Telegram-канал");
        c.setOnClickListener(v->{try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://t.me/+mBemTUK8OvhjN2Vi")));}catch(ActivityNotFoundException e){((TextView)((LinearLayout)r.getChildAt(1)).getChildAt(1)).setText("Нет приложения для открытия ссылки");}});return c;
    }
    private void showLanguages(){
        languagesOpen=true;LinearLayout page=col();page.setPadding(dp(18),dp(18),dp(18),dp(18));page.setBackgroundColor(Neon.BG);
        page.addView(action("back","Назад",false,()->show(0)));
        add(page,text("Языки",22,Neon.TEXT,true),15);add(page,text("Нажмите, чтобы выбрать язык. Удерживайте выбранный язык, чтобы убрать его из переключения.",12,Neon.MUTED,false),7);
        EditText search=new EditText(this);search.setSingleLine();search.setTextSize(15);search.setTextColor(Neon.TEXT);search.setHintTextColor(Neon.MUTED);search.setHint("Найти язык");search.setPadding(dp(14),dp(12),dp(14),dp(12));search.setBackground(Neon.shape(this,Neon.SURFACE,Neon.BORDER,14));add(page,search,14);
        ListView list=new ListView(this);list.setDivider(null);list.setCacheColorHint(Neon.BG);LinearLayout.LayoutParams listP=new LinearLayout.LayoutParams(-1,0,1);listP.topMargin=dp(12);page.addView(list,listP);
        List<String> filtered=new ArrayList<>();BaseAdapter adapter=new BaseAdapter(){
            public int getCount(){return filtered.size();}public Object getItem(int n){return filtered.get(n);}public long getItemId(int n){return n;}
            public View getView(int n,View recycled,ViewGroup parent){
                LinearLayout outer;
                if(recycled==null){outer=col();outer.setPadding(0,0,0,dp(7));LinearLayout item=tile();item.addView(text("",15,Neon.TEXT,true));add(item,text("",11,Neon.MUTED,false),4);outer.addView(item);}else outer=(LinearLayout)recycled;
                String code=filtered.get(n);LinearLayout item=(LinearLayout)outer.getChildAt(0);Set<String> selected=prefs.getStringSet("languages",new HashSet<>(Arrays.asList("ru","en")));
                ((TextView)item.getChildAt(0)).setText(LanguageCatalog.name(code)+(selected.contains(code)?"  ✓":""));((TextView)item.getChildAt(1)).setText(LanguageCatalog.detail(code));return outer;
            }
        };
        list.setAdapter(adapter);list.setOnItemClickListener((p,v,n,id)->{
            String code=filtered.get(n);Set<String> selected=new HashSet<>(prefs.getStringSet("languages",new HashSet<>(Arrays.asList("ru","en"))));selected.add(code);
            prefs.edit().putStringSet("languages",selected).putString("lang",code).apply();((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(search.getWindowToken(),0);show(0);
        });
        list.setOnItemLongClickListener((p,v,n,id)->{
            String code=filtered.get(n);Set<String> selected=new HashSet<>(prefs.getStringSet("languages",new HashSet<>(Arrays.asList("ru","en"))));
            if(selected.size()>1&&selected.remove(code)){SharedPreferences.Editor edit=prefs.edit().putStringSet("languages",selected);if(code.equals(prefs.getString("lang","ru")))edit.putString("lang",selected.iterator().next());edit.apply();adapter.notifyDataSetChanged();}return true;
        });
        java.util.function.Consumer<String> filter=q->{
            filtered.clear();String query=q.trim().toLowerCase(Locale.ROOT);
            for(String code:LanguageCatalog.all())if(query.isEmpty()||LanguageCatalog.name(code).toLowerCase(Locale.ROOT).contains(query)||new Locale(code).getDisplayLanguage(new Locale("ru")).toLowerCase(Locale.ROOT).contains(query)||code.contains(query))filtered.add(code);
            adapter.notifyDataSetChanged();
        };
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){filter.accept(s.toString());}public void afterTextChanged(Editable e){}});
        filter.accept("");root.removeAllViews();root.addView(page,new FrameLayout.LayoutParams(-1,-1));page.setFocusableInTouchMode(true);page.requestFocus();
    }
    @Override public void onBackPressed(){if(languagesOpen){show(0);return;}super.onBackPressed();}
}
