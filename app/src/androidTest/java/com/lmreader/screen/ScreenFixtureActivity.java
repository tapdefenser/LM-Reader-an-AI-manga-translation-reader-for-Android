package com.lmreader.screen;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;

/** Separate test APK window: realistic scrolling events and a continuously moving background. */
public class ScreenFixtureActivity extends Activity {
    private Fixture view;
    private android.app.Dialog popup;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        view = new Fixture(); setContentView(view); configure(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); configure(intent); }
    private void configure(Intent intent) {
        if (intent.hasExtra("orientation")) setRequestedOrientation(intent.getIntExtra("orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED));
        view.video = intent.getBooleanExtra("video", false);
        view.scale = intent.getFloatExtra("scale", 1f);
        view.shift = intent.getFloatExtra("shift", 0f);
        view.fixedControls = intent.getBooleanExtra("fixedControls", view.fixedControls);
        view.tintOpacity = intent.getFloatExtra("tintOpacity", 0f);
        view.tintColor = intent.getIntExtra("tintColor", Color.BLACK);
        view.inlinePopup = intent.getBooleanExtra("inlinePopup", false);
        view.invalidate();
        if (intent.hasExtra("popup")) {
            if (popup != null) { popup.dismiss(); popup = null; }
            if (intent.getBooleanExtra("popup", false)) {
                popup = new android.app.Dialog(this);
                android.widget.TextView message = new android.widget.TextView(this);
                message.setText("Fixture menu\nA temporary dialog"); message.setTextSize(24);
                message.setTextColor(Color.BLACK); message.setGravity(Gravity.CENTER); message.setBackgroundColor(Color.WHITE);
                popup.setContentView(message); popup.show();
                popup.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                WindowManager.LayoutParams params = popup.getWindow().getAttributes(); params.dimAmount = .65f;
                params.width = Math.round(getResources().getDisplayMetrics().widthPixels * .75f);
                params.height = Math.round(getResources().getDisplayMetrics().heightPixels * .35f);
                popup.getWindow().setAttributes(params);
            }
        }
    }
    private class Fixture extends View {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean video, dragged, held, fixedControls, inlinePopup; int variant; float scale = 1, shift, lastY; long start = android.os.SystemClock.uptimeMillis();
        float tintOpacity; int tintColor = Color.BLACK;
        final android.os.Handler gestures = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable longClick = () -> { if (!dragged) { held = true; performLongClick(); } };
        final ScaleGestureDetector scaling;
        Fixture() {
            super(ScreenFixtureActivity.this);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
            setOnClickListener(v -> { variant++; invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); });
            setOnLongClickListener(v -> { variant++; invalidate(); sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED); return true; });
            scaling = new ScaleGestureDetector(ScreenFixtureActivity.this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) { scale *= detector.getScaleFactor(); invalidate(); return true; }
            });
        }
        @Override protected void onDraw(Canvas canvas) {
            canvas.drawColor(Color.rgb(220, 225, 230));
            if (video) {
                long elapsed = android.os.SystemClock.uptimeMillis() - start;
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.rgb(80, 150, 200));
                float x = (elapsed / 3f) % (getWidth() + 600) - 600;
                canvas.drawRect(x, 0, x + 600, getHeight(), paint);
                paint.setColor(Color.WHITE); canvas.drawRect(70, getHeight() * .7f, getWidth() - 70, getHeight() * .92f, paint);
                paint.setColor(Color.BLACK); paint.setTextSize(getWidth() / 18f); paint.setTextAlign(Paint.Align.CENTER);
                boolean first = elapsed / 12000 % 2 == 0;
                canvas.drawText(first ? "The rabbit is blue." : "The weather is nice today.", getWidth() / 2f, getHeight() * .79f, paint);
                canvas.drawText("Let's meet at school tomorrow.", getWidth() / 2f, getHeight() * .86f, paint);
                postInvalidateOnAnimation(); return;
            }
            canvas.save(); canvas.translate(0, shift); canvas.scale(scale, scale);
            java.util.Random texture = new java.util.Random(572 + variant * 395L);
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(4);
            for (int i = 0; i < 350; i++) {
                float x = texture.nextInt(Math.max(1, getWidth())), y = texture.nextInt(3800);
                paint.setColor(Color.rgb(70 + texture.nextInt(80), 80, 100));
                canvas.drawRect(x, y, x + 15 + texture.nextInt(60), y + 15 + texture.nextInt(50), paint);
            }
            for (int page = 0; page < 5; page++) {
                float top = 150 + page * 700;
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
                canvas.drawOval(80, top, getWidth() - 80, top + 420, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(5); paint.setColor(Color.BLACK);
                canvas.drawOval(80, top, getWidth() - 80, top + 420, paint);
                paint.setStyle(Paint.Style.FILL); paint.setTextSize(getWidth() / 18f); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(variant == 0 ? "The rabbit is blue." : variant == 1 ? "A new story begins." : "Enjoy your adventure.", getWidth() / 2f, top + 160, paint);
                canvas.drawText(variant == 0 ? "Good morning, my friend!" : variant == 1 ? "Let's read the next page." : "Have a nice journey!", getWidth() / 2f, top + 250, paint);
                canvas.drawText("Page " + (page + 1), getWidth() / 2f, top + 520, paint);
            }
            canvas.restore();
            if (fixedControls) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
                canvas.drawRect(0, getHeight() - 110, getWidth(), getHeight(), paint);
                paint.setColor(Color.BLACK); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(42);
                canvas.drawText("Search for a book", getWidth() / 2f, getHeight() - 50, paint);
            }
            if (tintOpacity > 0) canvas.drawColor(Color.argb(Math.round(255 * tintOpacity),
                Color.red(tintColor), Color.green(tintColor), Color.blue(tintColor)));
            if (inlinePopup) {
                paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE);
                canvas.drawRect(getWidth() * .25f, getHeight() * .25f, getWidth() * .75f, getHeight() * .81f, paint);
                paint.setColor(Color.BLACK); paint.setTextSize(34); paint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("A temporary menu", getWidth() * .5f, getHeight() * .5f, paint);
            }
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            scaling.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { lastY = event.getY(); dragged = false; held = false; gestures.postDelayed(longClick, ViewConfiguration.getLongPressTimeout()); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && !scaling.isInProgress()) {
                float delta = event.getY() - lastY; lastY = event.getY(); shift += delta; invalidate();
                if (Math.abs(delta) > 1) { dragged = true; gestures.removeCallbacks(longClick); }
                AccessibilityEvent scrolled = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_SCROLLED);
                scrolled.setSource(this); scrolled.setClassName(getClass().getName()); scrolled.setPackageName(getContext().getPackageName());
                scrolled.setScrollable(true); scrolled.setScrollX(0); scrolled.setScrollY(Math.round(-shift));
                scrolled.setScrollDeltaX(0); scrolled.setScrollDeltaY(Math.round(-delta));
                getParent().requestSendAccessibilityEvent(this, scrolled); return true;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { gestures.removeCallbacks(longClick); if (!dragged && !held) performClick(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) gestures.removeCallbacks(longClick);
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true); info.setContentDescription("Screen translation fixture");
        }
    }
}
