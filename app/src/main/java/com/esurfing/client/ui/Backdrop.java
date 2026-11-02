package com.esurfing.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

/**
 * 背景层：竖直渐变 + 两团柔光。整块背景自绘，不占用图片资源，
 * 也被弹窗的高斯模糊当作「被模糊的内容」。
 */
public final class Backdrop extends View {

    private final Theme theme;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Shader base;
    private Shader glowA;
    private Shader glowB;

    public Backdrop(Context c, Theme theme) {
        super(c);
        this.theme = theme;
        setLayoutParams(new android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        base = new LinearGradient(0, 0, w * 0.35f, h, new int[]{theme.bg1, theme.bg2, theme.bg3},
                new float[]{0f, 0.52f, 1f}, Shader.TileMode.CLAMP);
        float r1 = Math.max(w, h) * 0.62f;
        glowA = new RadialGradient(-w * 0.18f, -h * 0.06f, r1,
                new int[]{theme.glow1, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
        float r2 = Math.max(w, h) * 0.55f;
        glowB = new RadialGradient(w * 1.06f, h * 0.94f, r2,
                new int[]{theme.glow2, 0x00000000}, new float[]{0f, 1f}, Shader.TileMode.CLAMP);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        if (base == null) {
            onSizeChanged(w, h, 0, 0);
        }
        paint.setShader(base);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(glowA);
        canvas.drawRect(0, 0, w, h, paint);
        paint.setShader(glowB);
        canvas.drawRect(0, 0, w, h, paint);
    }
}
