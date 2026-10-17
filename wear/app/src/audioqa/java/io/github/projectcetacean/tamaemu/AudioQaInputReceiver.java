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
        if (!expected.equals(intent.getComponent())) return;
        String action = intent.getAction();
        if ("singles".equals(action) || "same-c".equals(action) || "abc".equals(action) || "stop".equals(action))
            AudioQaInputProvider.command(action);
    }
}
