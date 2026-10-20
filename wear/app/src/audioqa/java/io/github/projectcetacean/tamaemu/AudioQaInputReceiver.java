package io.github.projectcetacean.tamaemu;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** DUMP-gated, explicit-only commands. Never starts or focuses an Activity. */
public final class AudioQaInputReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!AudioQaInputProvider.enabled(context) || intent == null) return;
        ComponentName expected = new ComponentName(AudioQaInputProvider.QA_PACKAGE, AudioQaInputReceiver.class.getName());
        if (!expected.equals(intent.getComponent())
                || (intent.getPackage()!=null && !AudioQaInputProvider.QA_PACKAGE.equals(intent.getPackage()))
                || intent.getData()!=null || intent.getClipData()!=null || intent.getSelector()!=null
                || intent.getCategories()!=null || (intent.getExtras()!=null && !intent.getExtras().isEmpty())) return;
        String action = intent.getAction();
        if ("singles".equals(action) || "same-c".equals(action) || "abc".equals(action) || "track-contract".equals(action) || "stop".equals(action) || "passive-scalars".equals(action) || "passive-stop".equals(action))
            AudioQaInputProvider.command(action);
    }
}
