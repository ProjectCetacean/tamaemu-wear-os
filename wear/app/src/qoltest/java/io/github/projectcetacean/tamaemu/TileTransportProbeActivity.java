package io.github.projectcetacean.tamaemu;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Explicit QA entry point only. It never asks the app for a session. */
public final class TileTransportProbeActivity extends Activity {
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (!TileTransportProbeState.enabled(this)) { finish(); return; }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER);
        int inset = dp(26);
        body.setPadding(inset, dp(16), inset, dp(16));
        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(14);
        body.addView(status);
        button(body, "Arm next Tile visit", () -> {
            TileTransportProbeState.get().arm(this);
            refresh();
        });
        button(body, "Stop", () -> {
            TileTransportProbeState.get().stop(this, "button_stop");
            refresh();
        });
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(body);
        setContentView(scroll);
        accept(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        accept(intent);
    }

    private void accept(Intent intent) {
        if (!TileTransportProbeState.enabled(this)) { finish(); return; }
        String action = intent == null ? null : intent.getStringExtra("action");
        if ("arm".equals(action)) TileTransportProbeState.get().arm(this);
        else if ("stop".equals(action)) TileTransportProbeState.get().stop(this, "explicit_stop");
        else if (action != null) TileTransportProbeState.get().event("ignored_action", "value=unknown");
        refresh();
    }

    @Override protected void onResume() {
        super.onResume();
        if (status != null) refresh();
    }

    private void refresh() {
        status.setText("Transport probe\nSYNTHETIC ONLY\n"
                + TileTransportProbeState.get().describe(this)
                + "\nBack, then visit the probe Tile.\n15 s maximum; visible cadence unknown.");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void button(LinearLayout body, String label, Runnable click) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setMinHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setOnClickListener(view -> click.run());
        body.addView(button, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));
    }
}
