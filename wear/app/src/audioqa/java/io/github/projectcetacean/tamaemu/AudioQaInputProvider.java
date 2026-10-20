package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.lang.ref.WeakReference;

/** Registers before Application.onCreate, without modifying the production lifecycle. */
public final class AudioQaInputProvider extends ContentProvider implements Application.ActivityLifecycleCallbacks {
    static final String QA_PACKAGE = "io.github.projectcetacean.tamaemu.qoltest";
    private static AudioQaInputProvider instance;
    private WeakReference<Activity> passiveOwner;
    final AudioQaInputDriver driver = new AudioQaInputDriver();

    static boolean enabled(Context context) {
        return context != null && QA_PACKAGE.equals(context.getPackageName())
                && (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
    @Override public boolean onCreate() {
        Context context = getContext();
        if (!enabled(context) || !(context.getApplicationContext() instanceof Application)) return false;
        instance = this;
        ((Application) context.getApplicationContext()).registerActivityLifecycleCallbacks(this);
        return true;
    }
    static void command(String action) {
        if (instance != null) instance.driver.command(action);
    }
    @Override public void onActivityResumed(Activity activity) {
        if (activity.getClass() == MainActivity.class && enabled(activity)) {
            if(passiveOwner!=null&&passiveOwner.get()!=activity)driver.passive.stop("owner_changed");
            passiveOwner=new WeakReference<>(activity);driver.resume((MainActivity) activity);
        }
    }
    private void passiveDeparture(Activity activity,String reason) {
        if(passiveOwner!=null&&passiveOwner.get()==activity){driver.passive.stop(reason);passiveOwner=null;}
    }
    @Override public void onActivityPaused(Activity activity) { passiveDeparture(activity,"pause");driver.depart(activity, "pause"); }
    @Override public void onActivityStopped(Activity activity) { passiveDeparture(activity,"stop_lifecycle");driver.depart(activity, "stop_lifecycle"); }
    @Override public void onActivityDestroyed(Activity activity) { passiveDeparture(activity,"destroy");driver.depart(activity, "destroy"); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
