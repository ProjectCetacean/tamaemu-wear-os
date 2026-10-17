package io.github.projectcetacean.tamaemu;

import android.annotation.TargetApi;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RuntimeShader;

/** Loaded only on API 33+. A capability failure falls back to the static Canvas gradient. */
@TargetApi(33)
final class RuntimeShaderGlass implements PixelView.Glass {
    private final RuntimeShader shader = new RuntimeShader(
            "uniform float2 origin; uniform float2 size; uniform float3 stops; "
            + "uniform float4 color0; uniform float4 color1; uniform float4 color2; "
            + "half4 main(float2 p) { "
            + "float t=clamp(dot(p-origin,size)/max(dot(size,size),1.0),0.0,1.0); "
            + "float4 c=t<stops.y?mix(color0,color1,clamp((t-stops.x)/(stops.y-stops.x),0.0,1.0))"
            + ":mix(color1,color2,clamp((t-stops.y)/(stops.z-stops.y),0.0,1.0)); "
            + "return half4(c.rgb*c.a,c.a); }");
    private final Paint paint = new Paint();
    RuntimeShaderGlass() {
        shader.setFloatUniform("stops", PixelView.GLASS_STOPS);
        for (int i = 0; i < PixelView.GLASS_COLORS.length; i++) {
            int color = PixelView.GLASS_COLORS[i];
            shader.setFloatUniform("color" + i, Color.red(color) / 255f,
                    Color.green(color) / 255f, Color.blue(color) / 255f, Color.alpha(color) / 255f);
        }
        paint.setShader(shader);
    }
    public void bounds(Rect destination) {
        shader.setFloatUniform("origin", (float) destination.left, (float) destination.top);
        shader.setFloatUniform("size", (float) destination.width(), (float) destination.height());
    }
    public void draw(Canvas canvas, Rect bounds) { canvas.drawRect(bounds, paint); }
}
