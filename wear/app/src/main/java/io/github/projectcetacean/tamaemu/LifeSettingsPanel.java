package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Life controls append to the existing foreground settings surface. */
final class LifeSettingsPanel implements SettingsPanel.Extension {
    interface Listener { void mode(boolean protectedAway); void retry(); }
    private final LifeUiModel model;
    private final Listener listener;
    private Button normal, protect, retry;
    private TextView progress, availability, clock, scheduler, saved;
    LifeSettingsPanel(LifeUiModel model, Listener listener) { this.model=model;this.listener=listener; }
    @Override public void append(LinearLayout content) {
        TextView heading=text(content,R.string.life_heading,15);
        UiChrome.heading(heading,false);
        if(android.os.Build.VERSION.SDK_INT>=28)heading.setAccessibilityHeading(true);
        normal=action(content,R.string.life_normal,()->listener.mode(false));
        text(content,R.string.life_normal_description,12);
        protect=action(content,R.string.life_protected,()->listener.mode(true));
        text(content,R.string.life_protected_description,12);
        text(content,R.string.life_clock_policy,12);
        progress=text(content,R.string.loading,13);
        progress.setTextColor(UiChrome.TEXT);
        availability=text(content,R.string.life_mode_unavailable,12);
        availability.setTextColor(UiChrome.TEXT);
        clock=text(content,R.string.life_clock_estimated,12);
        clock.setTextColor(UiChrome.TEXT);
        scheduler=text(content,R.string.life_scheduler_pending,12);
        scheduler.setTextColor(UiChrome.TEXT);
        saved=text(content,R.string.life_not_saved,12);
        retry=action(content,R.string.retry,listener::retry);
        refresh(null);
    }
    void refresh(CharSequence message) {
        if(normal==null)return;
        Context context=normal.getContext();
        selected(normal,model.selectedMode()==0);selected(protect,model.selectedMode()==1);
        normal.setEnabled(model.modeEnabled());protect.setEnabled(model.modeEnabled());
        progress.setText(message == null ? summary(context,model) : message);
        availability.setVisibility(model.modeEnabled()?View.GONE:View.VISIBLE);
        availability.setText(model.modePending()?R.string.life_mode_saving:
                model.modeRejected()?R.string.life_mode_rejected:R.string.life_mode_unavailable);
        // A rejected request is still useful feedback after the owner settles.
        if(model.modeRejected())availability.setVisibility(View.VISIBLE);
        LifeStatus status=model.status();
        clock.setVisibility(status!=null&&status.clockQuality!=0?View.VISIBLE:View.GONE);
        scheduler.setVisibility(status!=null&&status.schedulingFailed?View.VISIBLE:View.GONE);
        scheduler.setText(R.string.life_scheduling_failed);
        if(status!=null&&status.lastSuccessfulCommitUtcMs>0){
            SimpleDateFormat format=new SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'",Locale.getDefault());
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            saved.setText(context.getString(R.string.life_updated,format.format(new Date(status.lastSuccessfulCommitUtcMs))));
        }else saved.setText(R.string.life_not_saved);
        retry.setVisibility(retryAvailable(status)?View.VISIBLE:View.GONE);
    }
    static boolean retryAvailable(LifeStatus status) {
        return status!=null&&(status.state==LifeStatus.OwnerState.SAVE_FAILED||status.state==LifeStatus.OwnerState.RECOVERY_REQUIRED);
    }
    static String summary(Context context,LifeUiModel model) {
        LifeStatus status=model.status();
        if(status==null)return context.getString(R.string.loading);
        switch(status.state){
            case CATCHING_UP:return context.getString(R.string.life_catching_up,LifeUiModel.duration(status.debtMs));
            case SAVING:return context.getString(R.string.life_saving);
            case SAVE_FAILED:return context.getString(R.string.life_save_failed);
            case RECOVERY_REQUIRED:return context.getString(R.string.life_recovery_description);
            case ACCOUNTING_ERROR:return context.getString(R.string.life_accounting_error);
            case PLAYING:
                return context.getString(model.modePending()?R.string.life_mode_saving:R.string.life_ready);
            default:return context.getString(R.string.loading);
        }
    }
    private void selected(Button button,boolean value) {
        button.setSelected(value);
        button.setContentDescription(value?button.getContext().getString(R.string.life_selected,button.getText()):button.getText());
    }
    private TextView text(LinearLayout content,int resource,int size) {
        TextView view=new TextView(content.getContext());view.setText(resource);
        UiChrome.body(view);view.setTextSize(size);view.setGravity(Gravity.START);
        content.addView(view,new LinearLayout.LayoutParams(-1,-2));return view;
    }
    private Button action(LinearLayout content,int resource,Runnable click) {
        Button view=new Button(content.getContext());view.setText(resource);UiChrome.action(view,false);
        view.setOnClickListener(v->click.run());
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.bottomMargin=Math.round(4*content.getResources().getDisplayMetrics().density);
        content.addView(view,layout);return view;
    }
}
