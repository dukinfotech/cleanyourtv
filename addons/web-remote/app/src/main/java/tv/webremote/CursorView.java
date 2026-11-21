package tv.webremote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The mouse pointer drawn over everything by the accessibility service. It fades out after a few seconds of no use. */
final class CursorView extends View {

    private static final long HIDE_AFTER_MS = 5_000;

    private final Path arrow = new Path();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float scale;
    private float x, y;
    private boolean shown, dragging;

    private final Runnable hide = () -> {
        shown = false;
        invalidate();
    };

    CursorView(Context context) {
        super(context);
        scale = context.getResources().getDisplayMetrics().density * 1.25f;
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 19);
        arrow.lineTo(4.5f, 15);
        arrow.lineTo(7.5f, 22);
        arrow.lineTo(10.5f, 20.5f);
        arrow.lineTo(7.5f, 14);
        arrow.lineTo(13, 14);
        arrow.close();
        fill.setColor(0xFFFFFFFF);
        outline.setStyle(Paint.Style.STROKE);
        outline.setColor(0xFF000000);
        outline.setStrokeWidth(1.4f);
        outline.setStrokeJoin(Paint.Join.ROUND);
    }

    void moveTo(float newX, float newY) {
        x = newX;
        y = newY;
        shown = true;
        removeCallbacks(hide);
        postDelayed(hide, HIDE_AFTER_MS);
        invalidate();
    }

    void setDragging(boolean dragging) {
        this.dragging = dragging;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!shown) return;
        canvas.save();
        canvas.translate(x, y);
        if (dragging) {
            fill.setColor(0x66FF5252);
            canvas.drawCircle(0, 0, 18 * scale, fill);
            fill.setColor(0xFFFFFFFF);
        }
        canvas.scale(scale, scale);
        canvas.drawPath(arrow, fill);
        canvas.drawPath(arrow, outline);
        canvas.restore();
    }
}
