package io.github.projectcetacean.tamaemu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;

/** Presentation only. Never shares a preference file with guest state. */
final class UiPreferences {
    enum Size { SMALL, BALANCED, LARGE }
    enum Controls { VISIBLE, RETRACTABLE }
    enum ControlSpace { RESERVED, OVERLAY }
    enum Placement { BOTTOM, LEFT, RIGHT, TOP }
    final ControlSpace controlSpace;
    final Placement placement;
    enum Shape { CIRCLE, ROUNDED_SQUARE }
    enum Effect { CLEAN, SOFT_LCD, LCD_GLASS }
    enum Sharpness { OFF, SLIGHT, MEDIUM, STRONG }
    enum Shell { BLACK, SLATE, SAGE, PLUM }
    enum Buttons { CHARCOAL, SILVER, BLUE }
    final Size size;
    final Controls controls;
    final Shape shape;
    final Effect effect;
    final Sharpness sharpness;
    final Shell shell;
    final Buttons buttons;
    final boolean integerPixels, haptics, keepAwake, sound;

    private UiPreferences(Size size, Controls controls, ControlSpace controlSpace, Placement placement, Shape shape, Effect effect,
            Shell shell, Buttons buttons, boolean integerPixels, boolean haptics, boolean keepAwake, boolean sound) {
        this(size, controls, controlSpace, placement, shape, effect, shell, buttons, integerPixels, haptics, keepAwake, sound, Sharpness.SLIGHT);
    }
    private UiPreferences(Size size, Controls controls, ControlSpace controlSpace, Placement placement, Shape shape, Effect effect,
            Shell shell, Buttons buttons, boolean integerPixels, boolean haptics, boolean keepAwake, boolean sound, Sharpness sharpness) {
        this.size = size; this.controls = controls; this.controlSpace = controlSpace;
        this.placement = placement; this.shape = shape; this.effect = effect;
        this.shell = shell; this.buttons = buttons; this.integerPixels = integerPixels;
        this.sharpness = sharpness;
        this.haptics = haptics; this.keepAwake = keepAwake; this.sound = sound;
    }
    static UiPreferences defaults() {
        return new UiPreferences(Size.BALANCED, Controls.VISIBLE, ControlSpace.RESERVED, Placement.BOTTOM, Shape.CIRCLE, Effect.CLEAN,
                Shell.BLACK, Buttons.CHARCOAL, false, false, true, true);
    }
    static UiPreferences decode(Map<String, String> values) {
        UiPreferences d = defaults();
        Size size = read(Size.class, values.get("size"), d.size);
        Controls controls = read(Controls.class, values.get("controls"), d.controls);
        ControlSpace space = values.containsKey("controlSpace")
                ? read(ControlSpace.class, values.get("controlSpace"), d.controlSpace)
                : (size == Size.LARGE ? ControlSpace.OVERLAY : ControlSpace.RESERVED);
        return new UiPreferences(size, controls, space, read(Placement.class, values.get("placement"), d.placement),
                read(Shape.class, values.get("shape"), d.shape),
                read(Effect.class, values.get("effect"), d.effect), read(Shell.class, values.get("shell"), d.shell),
                read(Buttons.class, values.get("buttons"), d.buttons), bool(values.get("integerPixels"), false),
                bool(values.get("haptics"), false), bool(values.get("keepAwake"), true), bool(values.get("sound"), true), sharpness(values.get("sharpness")));
    }
    private static Sharpness sharpness(String value) {
        for (Sharpness choice : Sharpness.values()) {
            if (choice.name().toLowerCase(Locale.ROOT).equals(value)) return choice;
        }
        return Sharpness.SLIGHT;
    }
    private static <E extends Enum<E>> E read(Class<E> type, String value, E fallback) {
        try { return Enum.valueOf(type, value); } catch (IllegalArgumentException | NullPointerException ex) { return fallback; }
    }
    private static boolean bool(String value, boolean fallback) {
        return "true".equals(value) || (!"false".equals(value) && fallback);
    }
    Map<String, String> encode() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("size", size.name()); m.put("controls", controls.name()); m.put("shape", shape.name());
        m.put("controlSpace", controlSpace.name()); m.put("placement", placement.name());
        m.put("sharpness", sharpness.name().toLowerCase(Locale.ROOT));
        m.put("effect", effect.name()); m.put("shell", shell.name()); m.put("buttons", buttons.name());
        m.put("integerPixels", Boolean.toString(integerPixels)); m.put("haptics", Boolean.toString(haptics));
        m.put("keepAwake", Boolean.toString(keepAwake)); m.put("sound",Boolean.toString(sound)); return m;
    }
    UiPreferences withSize(Size value) {
        Map<String, String> m = encode(); m.put("size", value.name());
        return decode(m);
    }
    UiPreferences withControls(Controls value) {
        Map<String, String> m = encode(); m.put("controls", value.name());
        return decode(m);
    }
    UiPreferences with(String key, String value) {
        Map<String, String> m = encode(); m.put(key, value); return decode(m);
    }
    UiPreferences resetAppearance() {
        return defaults().with("haptics", Boolean.toString(haptics)).with("keepAwake", Boolean.toString(keepAwake)).with("sound",Boolean.toString(sound));
    }
}
