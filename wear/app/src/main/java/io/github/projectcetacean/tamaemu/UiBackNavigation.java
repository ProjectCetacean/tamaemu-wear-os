package io.github.projectcetacean.tamaemu;

import android.annotation.TargetApi;
import android.app.Activity;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Keep API 33 back types out of the API 26 activity's fields and signatures. */
@TargetApi(33)
final class UiBackNavigation {
    static Runnable register(Activity activity, Runnable action) {
        OnBackInvokedCallback callback = action::run;
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
        return () -> activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
    }
}
