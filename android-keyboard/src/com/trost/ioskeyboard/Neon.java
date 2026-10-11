package com.trost.ioskeyboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.View;
import android.widget.TextView;

final class Neon {
    static final int BG=0xff030b08, SURFACE=0xff0a1914, TILE=0xff10261d,
        TEXT=0xffedf5f0, MUTED=0xff91a19d, ACCENT=0xff64ff8f, BORDER=0xff285043;
    static int dp(Context c,float n) {return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static TextView text(Context c,String s,int size,int color,boolean bold) {
        TextView v=new TextView(c);v.setText(s);v.setTextSize(size);v.setTextColor(color);
        v.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return v;
    }
    static GradientDrawable shape(Context c,int fill,int border,int radius) {
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));
        if(border!=0)d.setStroke(dp(c,1),border);return d;
    }
    static Drawable button(Context c,int fill,int border,int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2264ff8f),shape(c,fill,border,radius),null);
    }
    static Drawable gradientButton(Context c) {
        GradientDrawable d=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xffb1ffc5,ACCENT});
        d.setCornerRadius(dp(c,22));d.setStroke(dp(c,1),0xffb1ffc5);
        return new RippleDrawable(ColorStateList.valueOf(0x2264ff8f),d,null);
    }
    static void pressAnimation(View view) {
        view.setOnTouchListener((target,event)->{
            int action=event.getActionMasked();
            if(action==android.view.MotionEvent.ACTION_DOWN)
                target.animate().scaleX(.97f).scaleY(.97f).setDuration(80).start();
            else if(action==android.view.MotionEvent.ACTION_UP||action==android.view.MotionEvent.ACTION_CANCEL)
                target.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
            return false;
        });
    }
    static final class Wallpaper extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Wallpaper(Context c){super(c);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight();c.drawColor(BG);
            p.setShader(new RadialGradient(w*.15f,h*.3f,w,new int[]{0x1564ff8f,0x0064ff8f},null,Shader.TileMode.CLAMP));
            c.drawRect(0,0,w,h,p);p.setShader(null);
            Path ribbon=new Path();ribbon.moveTo(-w*.1f,h*.86f);
            ribbon.cubicTo(w*.28f,h*.68f,w*.42f,h*1.04f,w*1.1f,h*.8f);
            ribbon.lineTo(w*1.1f,h*1.02f);ribbon.lineTo(0,h*1.02f);ribbon.close();
            p.setShader(new LinearGradient(0,h*.8f,w,h,new int[]{0xff0b2418,0xff020806,0xff0c2417},null,Shader.TileMode.CLAMP));
            c.drawPath(ribbon,p);p.setShader(null);p.setColor(0x6664ff8f);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(dp(getContext(),.65f));
            Path curve=new Path();curve.moveTo(-w*.1f,h*.86f);curve.cubicTo(w*.28f,h*.68f,w*.42f,h*1.04f,w*1.1f,h*.8f);
            c.drawPath(curve,p);p.setStyle(Paint.Style.FILL);
        }
    }
    static final class Icon extends View {
        private final Paint p=new Paint(3);private final String kind;private final int color;
        Icon(Context c,String kind,int color){super(c);this.kind=kind;this.color=color;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c) {
            c.save();c.scale(getWidth()/24f,getHeight()/24f);p.setColor(color);
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.65f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);
            switch(kind) {
                case "keyboard":
                    c.drawRoundRect(2,5,22,19,4,4,p);
                    for(int y=9;y<=12;y+=3)for(int x=6;x<=18;x+=4)c.drawPoint(x,y,p);
                    c.drawLine(7,16,17,16,p);break;
                case "gear":
                    c.drawCircle(12,12,6,p);c.drawCircle(12,12,2,p);
                    for(int i=0;i<8;i++){c.save();c.rotate(i*45,12,12);c.drawLine(12,2,12,5,p);c.restore();}break;
                case "sliders":
                    for(int y=6;y<=18;y+=6){c.drawLine(3,y,21,y,p);c.drawCircle(y==12?15:8,y,2,p);}break;
                case "palette":
                    Path k=new Path();k.moveTo(12,2);k.cubicTo(1,2,0,18,9,21);k.cubicTo(16,24,9,16,16,16);k.cubicTo(26,16,23,2,12,2);c.drawPath(k,p);
                    c.drawCircle(7,9,1,p);c.drawCircle(12,6,1,p);c.drawCircle(17,9,1,p);break;
                case "globe":c.drawCircle(12,12,9,p);c.drawOval(8,3,16,21,p);c.drawLine(3,12,21,12,p);break;
                case "sound":
                    Path s=new Path();s.moveTo(3,9);s.lineTo(7,9);s.lineTo(12,5);s.lineTo(12,19);s.lineTo(7,15);s.lineTo(3,15);s.close();c.drawPath(s,p);
                    c.drawArc(8,6,20,18,-50,100,false,p);c.drawArc(8,2,24,22,-50,100,false,p);break;
                case "telegram":
                    Path a=new Path();a.moveTo(2,10);a.lineTo(22,3);a.lineTo(18,21);a.lineTo(10,15);a.lineTo(8,20);a.lineTo(8,13);a.lineTo(18,7);a.lineTo(6,12);a.close();c.drawPath(a,p);break;
                case "arrow":c.drawLine(9,5,16,12,p);c.drawLine(16,12,9,19,p);break;
                case "back":c.drawLine(15,5,8,12,p);c.drawLine(8,12,15,19,p);break;
                default:c.drawRoundRect(6,3,18,21,3,3,p);c.drawLine(9,18,15,18,p);
            }
            c.restore();
        }
    }
    static final class Toggle extends View {
        private final Paint p=new Paint(3);private boolean checked;private final String label;
        Toggle(Context c,boolean value,String label,java.util.function.Consumer<Boolean> change) {
            super(c);checked=value;this.label=label;updateDescription();
            setOnClickListener(v->{checked=!checked;updateDescription();invalidate();change.accept(checked);});
            pressAnimation(this);
        }
        private void updateDescription(){setContentDescription(label+(checked?": включено":": выключено"));}
        @Override protected void onDraw(Canvas c) {
            float h=dp(getContext(),26),w=dp(getContext(),43),r=h/2f,x=(getWidth()-w)/2,y=(getHeight()-h)/2;
            p.setColor(checked?ACCENT:BORDER);c.drawRoundRect(x,y,x+w,y+h,r,r,p);
            p.setColor(checked?0xffeffff3:MUTED);c.drawCircle(x+(checked?w-r:r),y+r,r-dp(getContext(),3),p);
        }
        @Override public CharSequence getAccessibilityClassName(){return "android.widget.Switch";}
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setCheckable(true);info.setChecked(checked);}
    }
    static final class Preview extends View {
        private final Paint p=new Paint(3);private final ThemeConfig t;
        Preview(Context c,ThemeConfig t){super(c);this.t=t;}
        @Override protected void onDraw(Canvas c) {
            float w=getWidth(),h=getHeight();p.setColor(t.bg);c.drawRoundRect(0,0,w,h,8,8,p);
            float gap=w*.022f,kw=(w-gap*11)/10,kh=h*.17f;
            for(int r=0;r<3;r++)for(int i=0;i<10-r;i++){
                float x=gap+i*(kw+gap)+r*(kw+gap)*.35f,y=h*.14f+r*(kh+gap);
                p.setColor(t.key);c.drawRoundRect(x,y,x+kw,y+kh,2,2,p);
                p.setColor(t.text);c.drawCircle(x+kw/2,y+kh/2,Math.max(1,kw*.08f),p);
            }
            p.setColor(t.fn);c.drawRoundRect(w*.1f,h*.81f,w*.75f,h*.94f,3,3,p);
            p.setColor(t.accent);c.drawRoundRect(w*.8f,h*.81f,w*.96f,h*.94f,3,3,p);
        }
    }
}
