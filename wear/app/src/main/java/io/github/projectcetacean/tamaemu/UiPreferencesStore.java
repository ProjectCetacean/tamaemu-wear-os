package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.Map;

final class UiPreferencesStore {
    private final SharedPreferences file;
    UiPreferencesStore(Context context) { file = context.getSharedPreferences("ui_presentation", Context.MODE_PRIVATE); }
    UiPreferences load() {
        Map<String, String> values = new HashMap<>();
        for (Map.Entry<String, ?> entry : file.getAll().entrySet()) {
            if (entry.getValue() instanceof String) values.put(entry.getKey(), (String) entry.getValue());
        }
        return UiPreferences.decode(values);
    }
    void save(UiPreferences prefs) {
        SharedPreferences.Editor edit = file.edit();
        for (Map.Entry<String, String> entry : prefs.encode().entrySet()) edit.putString(entry.getKey(), entry.getValue());
        edit.apply();
    }
}
