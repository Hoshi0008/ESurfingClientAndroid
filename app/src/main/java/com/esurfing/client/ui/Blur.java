package com.esurfing.client.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.widget.ImageView;

/**
 * 弹窗的「磨砂 + 高斯模糊」：先把当前界面截成一张小图，再用 GPU 的
 * {@code RenderEffect} 做真·高斯模糊（API 31+ 才有，正好是本应用的最低版本），
 * 极端情况下（软件渲染）退化成 CPU 的多次均值模糊。
 */
public final class Blur {

    private Blur() {
    }

    /** 把 root 缩略成 1/{@code downscale} 的位图。 */
    public static Bitmap capture(View root, int downscale) {
        if (root == null) {
            return null;
        }
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) {
            return null;
        }
        int bw = Math.max(1, w / downscale);
        int bh = Math.max(1, h / downscale);
        try {
            Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bmp);
            canvas.scale(bw / (float) w, bh / (float) h);
            root.draw(canvas);
            return bmp;
        } catch (OutOfMemoryError | IllegalArgumentException e) {
            return null;
        }
    }

    /** 给 ImageView 施加高斯模糊（半径单位：像素）。 */
    public static void apply(ImageView iv, Bitmap bitmap, float radiusPx) {
        if (bitmap == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 31 && iv.isHardwareAccelerated() && radiusPx > 0.5f) {
            try {
                iv.setImageBitmap(bitmap);
                iv.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx,
                        Shader.TileMode.CLAMP));
                return;
            } catch (RuntimeException ignored) {
                // 少数设备 RenderEffect 不可用，落到 CPU 版本
            }
        }
        iv.setImageBitmap(boxBlur(bitmap, 6));
    }

    /** 三次均值模糊 ≈ 高斯模糊，用于软件渲染的兜底。 */
    private static Bitmap boxBlur(Bitmap src, int radius) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (radius <= 0 || w < 3 || h < 3) {
            return src;
        }
        int[] in = new int[w * h];
        int[] tmp = new int[w * h];
        src.getPixels(in, 0, w, 0, 0, w, h);
        for (int pass = 0; pass < 3; pass++) {
            blurRows(in, tmp, w, h, radius);
            blurCols(tmp, in, w, h, radius);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(in, 0, w, 0, 0, w, h);
        return out;
    }

    private static void blurRows(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[row + clamp(i, 0, w - 1)];
                a += (c >>> 24) & 0xFF;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                out[row + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int add = in[row + clamp(x + r + 1, 0, w - 1)];
                int sub = in[row + clamp(x - r, 0, w - 1)];
                a += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static void blurCols(int[] in, int[] out, int w, int h, int r) {
        int div = 2 * r + 1;
        for (int x = 0; x < w; x++) {
            int a = 0;
            int rr = 0;
            int gg = 0;
            int bb = 0;
            for (int i = -r; i <= r; i++) {
                int c = in[clamp(i, 0, h - 1) * w + x];
                a += (c >>> 24) & 0xFF;
                rr += (c >> 16) & 0xFF;
                gg += (c >> 8) & 0xFF;
                bb += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                out[y * w + x] = ((a / div) << 24) | ((rr / div) << 16) | ((gg / div) << 8) | (bb / div);
                int add = in[clamp(y + r + 1, 0, h - 1) * w + x];
                int sub = in[clamp(y - r, 0, h - 1) * w + x];
                a += ((add >>> 24) & 0xFF) - ((sub >>> 24) & 0xFF);
                rr += ((add >> 16) & 0xFF) - ((sub >> 16) & 0xFF);
                gg += ((add >> 8) & 0xFF) - ((sub >> 8) & 0xFF);
                bb += (add & 0xFF) - (sub & 0xFF);
            }
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
