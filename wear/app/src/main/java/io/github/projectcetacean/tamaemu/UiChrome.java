package io.github.projectcetacean.tamaemu;

import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.widget.Button;
import android.widget.TextView;

/** Shared presentation only. Native widgets retain their actions and accessibility labels. */
final class UiChrome {
    static final int TEXT=0xffeff4f8, MUTED=0xffa6b1bc, SURFACE=0xff161b22;
    static final int CARD=0xff242b35, ACCENT=0xffb8dac7, BORDER=0xff414c59;
    private UiChrome() {}

    static void action(Button button,boolean selected) {
        button.setSelected(selected);
        button.setAllCaps(false);
        button.setTextSize(13.5f);
        button.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        button.setSingleLine(false);button.setMaxLines(Integer.MAX_VALUE);button.setEllipsize(null);
        button.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);button.setMinimumWidth(0);
        button.setMinHeight(dp(button,48));button.setMinimumHeight(dp(button,48));
        button.setPadding(dp(button,12),dp(button,10),dp(button,12),dp(button,10));
        button.setTextColor(new ColorStateList(new int[][]{
                {-android.R.attr.state_enabled,android.R.attr.state_selected},
                {-android.R.attr.state_enabled},{android.R.attr.state_selected},{}},
                new int[]{0xff92ad9d,0xff78838f,ACCENT,TEXT}));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{-android.R.attr.state_enabled,android.R.attr.state_selected},card(button,0xff213029,0xff617a6c,1));
        states.addState(new int[]{-android.R.attr.state_enabled},card(button,SURFACE,0xff303842,1));
        states.addState(new int[]{android.R.attr.state_pressed},card(button,0xff34433e,ACCENT,1));
        states.addState(new int[]{android.R.attr.state_focused},card(button,CARD,ACCENT,2));
        states.addState(new int[]{android.R.attr.state_selected},card(button,0xff283b34,0xff63816f,1));
        states.addState(new int[]{},card(button,CARD,BORDER,1));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24ffffff),states,
                card(button,0xffffffff,0xffffffff,0)));
    }

    static android.graphics.drawable.Drawable overlayControl(android.graphics.drawable.Drawable artwork, boolean circle, int radius) {
        GradientDrawable backing = new GradientDrawable(); backing.setColor(SURFACE);
        backing.setShape(circle ? GradientDrawable.OVAL : GradientDrawable.RECTANGLE);
        if (!circle) backing.setCornerRadius(radius);
        return new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{backing, artwork});
    }

    static void heading(TextView view,boolean title) {
        view.setTextColor(title?TEXT:MUTED);
        view.setTextSize(title?17:11.5f);
        view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        view.setPadding(dp(view,2),dp(view,title?8:12),dp(view,2),dp(view,6));
    }

    static void body(TextView view) {
        view.setTextColor(MUTED);view.setTextSize(12);
        view.setPadding(dp(view,2),dp(view,5),dp(view,2),dp(view,7));
    }

    private static GradientDrawable card(TextView view,int color,int border,int width) {
        GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);
        drawable.setCornerRadius(dp(view,10));drawable.setStroke(dp(view,width),border);
        return drawable;
    }
    private static int dp(TextView view,int value) {
        return Math.round(value*view.getResources().getDisplayMetrics().density);
    }
}
