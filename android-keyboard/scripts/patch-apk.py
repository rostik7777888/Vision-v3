#!/usr/bin/env python3
from pathlib import Path
import re
import shutil
import sys
import xml.etree.ElementTree as ET

base,overlay,root=map(Path,sys.argv[1:])
app=base/'smali_classes3/com/trost/ioskeyboard'

def method(source,signature):
    matches=re.findall(r'^\.method '+re.escape(signature)+r'\n.*?^\.end method',source,re.M|re.S)
    assert len(matches)==1,(signature,len(matches))
    return matches[0]

def replace_method(path,signature,body):
    source=path.read_text();old=method(source,signature)
    path.write_text(source.replace(old,'.method '+signature+'\n'+body+'\n.end method',1))

def hook(path,signature,call):
    source=path.read_text();old=method(source,signature)
    changed,count=re.subn(r'(    \.locals \d+\n)',r'\1\n'+call+'\n',old,count=1)
    assert count==1;path.write_text(source.replace(old,changed,1))

native=app/'NativeDesign.smali';original_native=native.read_text()
view=app/'TypingView.smali';original_view=view.read_text()
service=app/'IosKeyboardService.smali';original_service=service.read_text()

# Keep the original keyboard geometry and both B toolbar methods intact.
replace_method(view,'public final setPal(Lcom/trost/ioskeyboard/Palette;)V','''    .locals 1
    invoke-virtual {p0}, Landroid/view/View;->getContext()Landroid/content/Context;
    move-result-object v0
    invoke-static {v0, p1}, Lcom/trost/ioskeyboard/ThemeConfig;->select(Landroid/content/Context;Lcom/trost/ioskeyboard/Palette;)Lcom/trost/ioskeyboard/Palette;
    move-result-object p1
    iput-object p1, p0, Lcom/trost/ioskeyboard/TypingView;->pal:Lcom/trost/ioskeyboard/Palette;
    return-void''')
source=view.read_text();signature='private final createKeys()V';old=method(source,signature)
changed=old.replace('    .line 66','''    invoke-virtual/range {p0 .. p0}, Lcom/trost/ioskeyboard/TypingView;->getLanguage()Ljava/lang/String;
    move-result-object v0
    invoke-static {v0}, Lcom/trost/ioskeyboard/LanguageCatalog;->use(Ljava/lang/String;)V

    .line 66''',1)
assert changed!=old;view.write_text(source.replace(old,changed,1))
chars=app/'TypingView$createKeys$chars$1.smali';source=chars.read_text();needle='    move-object/from16 v0, p1\n'
assert source.count(needle)==1
chars.write_text(source.replace(needle,needle+'''    invoke-static {v0}, Lcom/trost/ioskeyboard/LanguageCatalog;->row(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v0
''',1))

source=service.read_text()
pattern=r'(?P<start>    :cond_17\n    iget-object v0, p0, Lcom/trost/ioskeyboard/IosKeyboardService;->lang:Ljava/lang/String;\n).*?    :cond_18\n(?P<end>    iput-object v1, p0, Lcom/trost/ioskeyboard/IosKeyboardService;->lang:Ljava/lang/String;)'
source,count=re.subn(pattern,r'\g<start>\n    invoke-static {p0, v0}, Lcom/trost/ioskeyboard/LanguageCatalog;->next(Landroid/content/Context;Ljava/lang/String;)Ljava/lang/String;\n    move-result-object v1\n\g<end>',source,flags=re.S)
assert count==1;service.write_text(source)
hook(service,'private final press(Ljava/lang/String;)V','    invoke-static {p0, p1}, Lcom/trost/ioskeyboard/Lexicon;->observe(Landroid/inputmethodservice/InputMethodService;Ljava/lang/String;)V')
hook(service,'private final choose(Ljava/lang/String;)V', '''    iget-object v0, p0, Lcom/trost/ioskeyboard/IosKeyboardService;->lang:Ljava/lang/String;
    invoke-static {p0, v0, p1}, Lcom/trost/ioskeyboard/Lexicon;->observeSelection(Landroid/inputmethodservice/InputMethodService;Ljava/lang/String;Ljava/lang/String;)V''')

source=native.read_text();signature='public static key(Lcom/trost/ioskeyboard/TypingView;Landroid/graphics/Canvas;Lcom/trost/ioskeyboard/TypingView$Key;Z)V';old=method(source,signature)
changed,count=re.subn(r'    const-string v1, "ru".*?    :goto_3\n    move-object v1, v0','''    invoke-static {v0}, Lcom/trost/ioskeyboard/LanguageCatalog;->name(Ljava/lang/String;)Ljava/lang/String;
    move-result-object v1''',old,count=1,flags=re.S)
assert count==1;native.write_text(source.replace(old,changed,1))

online=app/'OnlineT9.smali'
for signature,body in [
    ('public static start(Landroid/inputmethodservice/InputMethodService;Lcom/trost/ioskeyboard/TypingView;)V','    .locals 0\n    return-void'),
    ('public static observe(Ljava/lang/String;Ljava/lang/String;)V','    .locals 0\n    return-void'),
    ('public static pause()V','    .locals 0\n    return-void'),
    ('public static eligible(Landroid/view/inputmethod/EditorInfo;Ljava/lang/String;)Z','    .locals 1\n    const/4 v0, 0x0\n    return v0')]:
    replace_method(online,signature,body)

for java in (root/'src/com/trost/ioskeyboard').glob('*.java'):
    for old in app.glob(java.stem+'*.smali'):
        if old.name==java.stem+'.smali' or old.name.startswith(java.stem+'$'):old.unlink()
for smali in (overlay/'smali').rglob('*.smali'):
    dest=base/'smali_classes3'/smali.relative_to(overlay/'smali');dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(smali,dest)
for sound in (root/'res/raw').glob('*.wav'):shutil.copy2(sound,base/'res/raw'/sound.name)
shutil.copy2(root/'assets/SOUND-NOTICE.txt',base/'assets/SOUND-NOTICE.txt')

ns='http://schemas.android.com/apk/res/android';ET.register_namespace('android',ns)
manifest=ET.parse(base/'AndroidManifest.xml');m=manifest.getroot();m.set('package','com.trost.keyboard.restore');m.set('{'+ns+'}versionCode','13');m.set('{'+ns+'}versionName','1.9.2')
for perm in list(m.findall('uses-permission')):
    if perm.get('{'+ns+'}name')=='android.permission.INTERNET':m.remove(perm)
m.find('application').set('{'+ns+'}label','trost 1.9.2')
manifest.write(base/'AndroidManifest.xml',encoding='utf-8',xml_declaration=True)
yml=(base/'apktool.yml').read_text().replace('versionCode: 10','versionCode: 13').replace('versionName: 1.8','versionName: 1.9.2');(base/'apktool.yml').write_text(yml)
strings=ET.parse(base/'res/values/strings.xml')
for string in strings.getroot():
    if string.get('name') in ('app_name','keyboard_name'):string.text='trost 1.9.2'
strings.write(base/'res/values/strings.xml',encoding='utf-8',xml_declaration=True)
(base/'res/values/styles.xml').write_text('''<resources><style name="AppTheme" parent="@android:style/Theme.Material.NoActionBar">
<item name="android:windowBackground">#030b08</item><item name="android:colorAccent">#64ff8f</item>
<item name="android:statusBarColor">#030b08</item><item name="android:navigationBarColor">#030b08</item>
<item name="android:windowLightStatusBar">false</item><item name="android:windowLightNavigationBar">false</item>
</style></resources>''')

for signature in ['public static toolbarTouch(Lcom/trost/ioskeyboard/TypingView;Landroid/view/MotionEvent;)Z','public static toolbar(Lcom/trost/ioskeyboard/TypingView;Landroid/graphics/Canvas;)V']:
    assert method(original_native,signature)==method(native.read_text(),signature),'B toolbar must remain unchanged'
for signature in ['protected onDraw(Landroid/graphics/Canvas;)V','protected onMeasure(II)V']:
    assert method(original_view,signature)==method(view.read_text(),signature),'Keyboard draw/measure must remain unchanged'
old_press=method(original_service,'private final press(Ljava/lang/String;)V');new_press=method(service.read_text(),'private final press(Ljava/lang/String;)V')
language_fragment = re.search(pattern, old_press, re.S).group(0)
replacement_fragment = re.search(r'    :cond_17\n    iget-object v0, p0, Lcom/trost/ioskeyboard/IosKeyboardService;->lang:Ljava/lang/String;\n\n    invoke-static.*?    iput-object v1, p0, Lcom/trost/ioskeyboard/IosKeyboardService;->lang:Ljava/lang/String;', new_press, re.S).group(0)
verified_press = new_press.replace(replacement_fragment, language_fragment, 1)
verified_press = verified_press.replace('\n    invoke-static {p0, p1}, Lcom/trost/ioskeyboard/Lexicon;->observe(Landroid/inputmethodservice/InputMethodService;Ljava/lang/String;)V\n', '', 1)
assert old_press == verified_press, 'No other key handlers, including B, may change'
print('1.9 features restored; original keyboard/B intact; adaptive offline T9 and MP3 clips installed')
