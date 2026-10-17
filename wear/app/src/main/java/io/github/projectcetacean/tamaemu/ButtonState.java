package io.github.projectcetacean.tamaemu;

import java.util.HashMap;
import java.util.Map;

interface ButtonSink { void button(int button, boolean pressed); }

/** Contacts retain their initial owner. Guest edges merge physical and timed sources. */
final class ButtonState {
    private static final class Contact {
        final int button; boolean inside;
        Contact(int button, boolean inside) { this.button = button; this.inside = inside; }
    }
    private final ButtonSink sink;
    private final Map<Integer, Contact> contacts = new HashMap<>();
    private final long[] pulses = new long[3];
    private final boolean[] pulseActive = new boolean[3];
    private boolean enabled = true;
    private int mask;
    ButtonState(ButtonSink sink) { this.sink = sink; }
    void contact(int button, int pointerId, boolean inside) {
        if (!enabled || button < 0 || button > 2) return;
        Contact owner = contacts.get(pointerId);
        if (owner == null) contacts.put(pointerId, new Contact(button, inside));
        else if (owner.button == button) owner.inside = inside;
        publish();
    }
    void endContact(int pointerId) { contacts.remove(pointerId); publish(); }
    void accessibilityPulse(int button, long token) {
        if (!enabled || button < 0 || button > 2) return;
        pulses[button] = token; pulseActive[button] = true; publish();
    }
    void endPulse(int button, long token) {
        if (button >= 0 && button < 3 && pulseActive[button] && pulses[button] == token) {
            pulseActive[button] = false; publish();
        }
    }
    void releaseAll() {
        contacts.clear();
        for (int i = 0; i < 3; i++) pulseActive[i] = false;
        publish();
    }
    void setEnabled(boolean enabled) { this.enabled = enabled; if (!enabled) releaseAll(); }
    int pressedMask() { return mask; }
    boolean hasActiveInput() {
        if (!contacts.isEmpty()) return true;
        for (boolean active : pulseActive) if (active) return true;
        return false;
    }
    private void publish() {
        int next = 0;
        for (Contact owner : contacts.values()) if (owner.inside) next |= 1 << owner.button;
        for (int i = 0; i < 3; i++) if (pulseActive[i]) next |= 1 << i;
        int changed = mask ^ next; mask = next;
        for (int i = 0; i < 3; i++) if ((changed & (1 << i)) != 0) sink.button(i, (next & (1 << i)) != 0);
    }
}
