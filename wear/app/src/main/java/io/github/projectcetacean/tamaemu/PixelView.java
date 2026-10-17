package io.github.projectcetacean.tamaemu;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import java.util.Arrays;

/** Raw source frames remain unchanged; contrast changes only the upload copy, with Canvas overlays. */
final class PixelView extends View {
    static final int GRID_ALPHA = 22;
    static final int EDGE_ALPHA = 21;
    static final int[] GLASS_COLORS = {0x14ffffff, 0x02ffffff, 0x0a000000};
    static final float[] GLASS_STOPS = {0, .55f, 1};
    private final Paint pixels = new Paint();
    private final Paint overlay = new Paint();
    private final Rect destination = new Rect();
    private Bitmap bitmap;
    private int[] raw, display;
    private static final int[][] CONTRAST = contrastTables();
    private static final int[] GAP_ALPHA = {0, 16, 32, 48};
    private UiPreferences.Sharpness sharpness = UiPreferences.Sharpness.SLIGHT;
    private UiPreferences.Effect effect = UiPreferences.Effect.CLEAN;
    private LinearGradient glass;
    private final Rect glassDestination = new Rect();
    interface Glass {
        void bounds(Rect destination);
        void draw(Canvas canvas, Rect destination);
    }
    interface GlassFactory {
        boolean eligible(Canvas canvas);
        Glass create();
    }
    private final GlassFactory glassFactory;
    private Glass runtimeGlass;
    private boolean shaderDisabled;
    private final Rect shaderDestination = new Rect();
    PixelView(Context context) {
        this(context, new GlassFactory() {
            public boolean eligible(Canvas canvas) { return Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated(); }
            public Glass create() { return new RuntimeShaderGlass(); }
        });
    }
    PixelView(Context context, GlassFactory glassFactory) {
        super(context);
        this.glassFactory = glassFactory;
        pixels.setFilterBitmap(false); pixels.setAntiAlias(false); pixels.setDither(false);
    }
    void appearance(UiPreferences prefs) {
        effect = prefs.effect;
        if (sharpness != prefs.sharpness) {
            sharpness = prefs.sharpness;
            if (bitmap != null) upload();
        }
        invalidate();
    }
    void frame(int width, int height, int[] frame) {
        if (width <= 0 || height <= 0 || (long) width * height != frame.length) return;
        boolean sameSize = bitmap != null && bitmap.getWidth() == width && bitmap.getHeight() == height;
        if (sameSize && Arrays.equals(raw, frame)) return;
        if (!sameSize) bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (raw == null || raw.length != frame.length) {
            raw = new int[frame.length]; display = new int[frame.length];
        }
        System.arraycopy(frame, 0, raw, 0, frame.length);
        upload();
        invalidate();
    }
    private static int[][] contrastTables() {
        double[] gains = {1.00, 1.06, 1.12, 1.20};
        int[][] tables = new int[gains.length][256];
        for (int level = 0; level < gains.length; level++) for (int value = 0; value < 256; value++) {
            tables[level][value] = (int) Math.max(0, Math.min(255,
                    Math.round((value - 127.5) * gains[level] + 127.5)));
        }
        return tables;
    }
    private void upload() {
        int[] upload = raw;
        if (sharpness != UiPreferences.Sharpness.OFF) {
            int[] lut = CONTRAST[sharpness.ordinal()];
            for (int i = 0; i < raw.length; i++) {
                int color = raw[i];
                display[i] = (color & 0xff000000) | (lut[(color >>> 16) & 255] << 16)
                        | (lut[(color >>> 8) & 255] << 8) | lut[color & 255];
            }
            upload = display;
        }
        bitmap.setPixels(upload, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
    }
    /** Copy boundary for future session/snapshot consumers. Never expose the renderer's array. */
    int[] copyRawFrame() { return raw == null ? null : raw.clone(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        float scale = Math.min((float) getWidth() / bitmap.getWidth(), (float) getHeight() / bitmap.getHeight());
        int w = Math.round(bitmap.getWidth() * scale), h = Math.round(bitmap.getHeight() * scale);
        int left = (getWidth() - w) / 2, top = (getHeight() - h) / 2;
        destination.set(left, top, left + w, top + h);
        canvas.drawBitmap(bitmap, null, destination, pixels);
        int gapAlpha = Math.max(effect == UiPreferences.Effect.CLEAN ? 0 : GRID_ALPHA,
                GAP_ALPHA[sharpness.ordinal()]);
        if (effect == UiPreferences.Effect.CLEAN && gapAlpha == 0) return;
        int save = canvas.save(); canvas.clipRect(destination);
        overlay.setShader(null); overlay.setColor(gapAlpha << 24);
        if (scale >= 2 && gapAlpha > 0) {
            // Explicit output-pixel strips leave cell centres untouched, including at exactly 2x.
            for (int x = 1; x < bitmap.getWidth(); x++) {
                int px = left + (int) (x * scale); canvas.drawRect(px, top, px + 1, top + h, overlay);
            }
            for (int y = 1; y < bitmap.getHeight(); y++) {
                int py = top + (int) (y * scale); canvas.drawRect(left, py, left + w, py + 1, overlay);
            }
        }
        if (effect == UiPreferences.Effect.CLEAN) {
            canvas.restoreToCount(save); return;
        }
        // A full inner band, rather than a centred stroke clipped to half its width.
        int band = scale >= 2 ? (int) Math.ceil(scale) : 1;
        overlay.setColor(EDGE_ALPHA << 24);
        canvas.drawRect(left, top, left + w, top + band, overlay);
        canvas.drawRect(left, top + h - band, left + w, top + h, overlay);
        canvas.drawRect(left, top + band, left + band, top + h - band, overlay);
        canvas.drawRect(left + w - band, top + band, left + w, top + h - band, overlay);
        if (effect == UiPreferences.Effect.LCD_GLASS) {
            boolean drawn = false;
            if (!shaderDisabled && glassFactory.eligible(canvas)) {
                try {
                    if (runtimeGlass == null) runtimeGlass = glassFactory.create();
                    if (!shaderDestination.equals(destination)) {
                        runtimeGlass.bounds(destination); shaderDestination.set(destination);
                    }
                    runtimeGlass.draw(canvas, destination); drawn = true;
                } catch (RuntimeException | LinkageError ex) {
                    runtimeGlass = null; shaderDisabled = true;
                }
            }
            if (!drawn) {
                if (!glassDestination.equals(destination)) {
                    glass = new LinearGradient(left, top, left + w, top + h,
                            GLASS_COLORS, GLASS_STOPS, Shader.TileMode.CLAMP);
                    glassDestination.set(destination);
                }
                overlay.setShader(glass); overlay.setColor(Color.WHITE);
                canvas.drawRect(destination, overlay); overlay.setShader(null);
            }
        }
        canvas.restoreToCount(save);
    }
}
