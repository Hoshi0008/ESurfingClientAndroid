package com.esurfing.client.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** 状态圆点：实心圆 + 一圈淡色光晕，比纯色圆更有层次。 */
public final class Dot extends View {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int color;

    public Dot(Context c, int color) {
        super(c);
        this.color = color;
        int size = Ui.dp(c, 18);
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(size, size));
    }

    public void setColor(int color) {
        this.color = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        float cx = w / 2f;
        float cy = h / 2f;
        paint.setColor(Ui.alpha(color, 0.18f));
        canvas.drawCircle(cx, cy, Math.min(w, h) / 2f, paint);
        paint.setColor(color);
        canvas.drawCircle(cx, cy, Math.min(w, h) / 2f * 0.52f, paint);
    }
}
