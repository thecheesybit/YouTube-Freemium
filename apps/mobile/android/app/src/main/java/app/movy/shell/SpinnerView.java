package app.movy.shell;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Thin rotating arc (our own design), 48dp by default. Animates only while attached. */
public final class SpinnerView extends View {
    public static final int RED = 0xFFE50914;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final ValueAnimator spin = ValueAnimator.ofFloat(0f, 360f);
    private float angle;

    public SpinnerView(Context ctx) {
        super(ctx);
        float d = ctx.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(3f * d);
        paint.setColor(RED);
        spin.setDuration(900);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        spin.addUpdateListener(a -> {
            angle = (float) a.getAnimatedValue();
            invalidate();
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        spin.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        spin.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float pad = paint.getStrokeWidth();
        oval.set(pad, pad, getWidth() - pad, getHeight() - pad);
        c.drawArc(oval, angle, 100f, false, paint);
    }
}
