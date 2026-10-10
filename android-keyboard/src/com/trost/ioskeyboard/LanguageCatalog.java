package com.trost.ioskeyboard;

import android.content.Context;
import java.util.*;

public final class LanguageCatalog {
    private static String active="ru";
    private static final Map<String,String[]> ROWS=new HashMap<>();
    static {
        ROWS.put("de",new String[]{"qwertzuiopü","asdfghjklöä","yxcvbnmß"});
        ROWS.put("fr",new String[]{"azertyuiop","qsdfghjklm","wxcvbn"});
        ROWS.put("es",new String[]{"qwertyuiop","asdfghjklñ","zxcvbnm"});
        ROWS.put("pt",new String[]{"qwertyuiop","asdfghjklç","zxcvbnm"});
        ROWS.put("tr",new String[]{"qwertyuıopğü","asdfghjklşi","zxcvbnmöç"});
        ROWS.put("uk",new String[]{"йцукенгшщзх","фівапролджє","ячсмитьбю"});
        ROWS.put("el",new String[]{";ςερτυθιοπ","ασδφγηξκλ","ζχψωβνμ"});
        ROWS.put("he",new String[]{"קראטוןםפ","שדגכעיחלךף","זסבהנמצתץ"});
    }
    public static void use(String language){active=language;}
    public static String row(String original){return row(active,original);}
    public static String name(String code) {
        if("ru".equals(code))return "Русский";
        if("en".equals(code))return "English";
        Locale locale=new Locale(code);return locale.getDisplayLanguage(locale);
    }
    public static String detail(String code) {
        if("ru".equals(code)||"en".equals(code))return "Раскладка и локальный словарь";
        if(ROWS.containsKey(code))return "Базовая раскладка · обучение на ваших словах";
        if(Arrays.asList("zh","ja","ko").contains(code))return "Латиница · конвертация CJK не поддерживается";
        return "Базовая латиница · нативная раскладка не реализована";
    }
    public static String row(String lang,String original) {
        String[] rows=ROWS.get(lang);if(rows==null)return original;
        if("qwertyuiop".equals(original))return rows[0];
        if("asdfghjkl".equals(original))return rows[1];
        if("zxcvbnm".equals(original))return rows[2];
        return original;
    }
    public static String next(Context context,String current) {
        Set<String> chosen=ThemeConfig.prefs(context).getStringSet("languages",new HashSet<>(Arrays.asList("ru","en")));
        List<String> languages=new ArrayList<>(chosen);Collections.sort(languages);
        if(languages.isEmpty())return "ru";
        return languages.get((languages.indexOf(current)+1)%languages.size());
    }
    public static List<String> all() {
        List<String> codes=new ArrayList<>(Arrays.asList(Locale.getISOLanguages()));
        Collections.sort(codes,(a,b)->name(a).compareToIgnoreCase(name(b)));return codes;
    }
}
