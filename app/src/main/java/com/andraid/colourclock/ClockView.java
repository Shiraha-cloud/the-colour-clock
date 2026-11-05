package com.andraid.colourclock;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The whole clock in one View: no view hierarchy, no library, one draw pass per step.
 * Geometry follows thecolourclock.com stylesheet (clamp/vmin values, 800px breakpoint).
 */
public class ClockView extends View {

    public static final int MODE_TIME = 0;
    public static final int MODE_COLOUR = 1;

    static final String PREFS = "colour_clock";
    static final String PREF_MODE = "mode";

    /** The last hex digit steps every 62.5 ms, so a 60 ms poll never skips a colour. */
    private static final long TICK_IDLE_MS = 60;
    private static final long TICK_ANIM_MS = 16;
    private static final long ABOUT_MS = 400;
    private static final long SWAP_MS = 450;

    private static final int WIDE_BREAKPOINT_DP = 800;
    private static final int ABOUT_BAR_DP = 80;
    private static final int ABOUT_TOP_DP = 96;

    private static final int HIT_NONE = 0;
    private static final int HIT_INFO = 1;
    private static final int HIT_TIME = 2;
    private static final int HIT_COLOUR = 3;
    private static final int HIT_READOUT = 4;
    private static final int HIT_CREDIT = 5;
    private static final int HIT_FONT = 6;

    private static final String URL_DESIGNER = "https://www.designwithjack.com";
    private static final String URL_FONT = "https://cotypefoundry.com/our-fonts/aeonik-mono/";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    private Calendar calendar;
    private String zoneId;

    private final Paint overlayPaint = new Paint();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint brandPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint readoutPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pillPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint creditPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Path shape = new Path();
    private final RectF cardRect = new RectF();
    private final RectF contentRect = new RectF();
    private final RectF infoRect = new RectF();
    private final RectF timeRect = new RectF();
    private final RectF colourRect = new RectF();
    private final RectF creditRect = new RectF();
    private final RectF fontRect = new RectF();
    private final RectF readoutRect = new RectF();
    private final char[] timeChars = "00:00:00".toCharArray();
    /** Reused so the 16 fps draw pass allocates nothing. */
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    private String[] bodyLines, fontLines, madeLines;

    private String brandText, timeLabel, colourLabel, headingText, bodyText, creditText, fontText, madeText;

    private int mode = MODE_TIME;
    private int prevMode = MODE_TIME;
    private int pressed = HIT_NONE;

    private boolean night;
    private boolean wide;
    private boolean ticking;

    private String hex = "000000";
    private String hexLabel = "#000000";
    private String timeString = "00:00:00";
    private int hexColour = Color.BLACK;
    private int ink = Color.BLACK;
    private int cardInk = Color.WHITE;
    private int pillBorder = 0xCCFFFFFF;
    private int pageBackground = Color.WHITE;

    private long swapStart = -1L;
    private float swapProgress = 1f;
    private long aboutStart = -1L;
    private float aboutFrom, aboutTo, aboutProgress;

    private int safeL, safeT, safeR, safeB;

    private float headerPad, infoSize, pillHeight, pillGap, pillBottom, pillPadH;
    private float brandTracking, pillTracking, readoutSize, readoutBaseline;
    private float aboutPad, aboutGap, headingTracking;
    private float contentLeft, contentTop, contentRight, contentBottom;
    private float brandMid, pillMid;
    private int aboutWidth;
    private Shader pillShader;

    public ClockView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.app_name));

        night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;

        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
        brandPaint.setTypeface(mono);
        readoutPaint.setTypeface(mono);
        pillPaint.setTypeface(mono);
        headingPaint.setTypeface(mono);
        bodyPaint.setTypeface(mono);
        captionPaint.setTypeface(mono);
        creditPaint.setTypeface(mono);
        strokePaint.setStyle(Paint.Style.STROKE);
        overlayPaint.setStyle(Paint.Style.FILL);
        solidPaint.setStyle(Paint.Style.FILL);

        brandText = context.getString(R.string.brand);
        timeLabel = context.getString(R.string.mode_time);
        colourLabel = context.getString(R.string.mode_colour);
        headingText = context.getString(R.string.about_heading).toUpperCase(Locale.ROOT);
        bodyText = context.getString(R.string.about_body);
        creditText = context.getString(R.string.about_credit).toUpperCase(Locale.ROOT);
        fontText = context.getString(R.string.about_font);
        madeText = context.getString(R.string.about_made);

        refreshPalette();
    }

    // ---------------------------------------------------------------- public state

    /** Startup path: no crossfade from the default mode, just show what was saved. */
    public void restoreMode(int savedMode) {
        mode = savedMode;
        prevMode = savedMode;
        swapProgress = 1f;
        swapStart = -1L;
    }

    public void setMode(int newMode) {
        if (newMode == mode) {
            return;
        }
        prevMode = mode;
        mode = newMode;
        swapProgress = 0f;
        swapStart = SystemClock.uptimeMillis();
        storeMode();
        wake();
    }

    public int getMode() {
        return mode;
    }

    public void startTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        handler.post(ticker);
    }

    public void stopTicking() {
        ticking = false;
        handler.removeCallbacks(ticker);
    }

    public void setSafeInsets(int left, int top, int right, int bottom) {
        if (safeL == left && safeT == top && safeR == right && safeB == bottom) {
            return;
        }
        safeL = left;
        safeT = top;
        safeR = right;
        safeB = bottom;
        relayout();
    }

    /** Night mode, density or font metrics moved; the Calendar is rebuilt so time zones land too. */
    public void onEnvironmentChanged(Configuration config) {
        night = (config.uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        calendar = null;
        refreshPalette();
        relayout();
    }

    private void relayout() {
        if (getWidth() > 0 && getHeight() > 0) {
            layout();
            invalidate();
        }
    }

    // ---------------------------------------------------------------- timing

    private void wake() {
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void tick() {
        if (!ticking) {
            return;
        }
        boolean changed = readClock();
        boolean animating = stepSwap() | stepAbout();
        if (changed | animating) {
            invalidate();
        }
        handler.postDelayed(ticker, animating ? TICK_ANIM_MS : TICK_IDLE_MS);
    }

    private boolean readClock() {
        String zone = TimeZone.getDefault().getID();
        if (calendar == null || !zone.equals(zoneId)) {
            calendar = Calendar.getInstance();
            zoneId = zone;
        }
        calendar.setTimeInMillis(System.currentTimeMillis());
        int hours = calendar.get(Calendar.HOUR_OF_DAY);
        int minutes = calendar.get(Calendar.MINUTE);
        int seconds = calendar.get(Calendar.SECOND);
        int millis = calendar.get(Calendar.MILLISECOND);

        boolean dirty = hours != lastHours | minutes != lastMinutes | seconds != lastSeconds;
        if (dirty) {
            lastHours = hours;
            lastMinutes = minutes;
            lastSeconds = seconds;
            pair(0, hours);
            pair(3, minutes);
            pair(6, seconds);
            timeString = new String(timeChars);
        }

        String next = ColourEngine.hex(hours, minutes, seconds, millis);
        if (!next.equals(hex)) {
            hex = next;
            refreshPalette();
            dirty = true;
        }
        return dirty;
    }

    private int lastHours = -1, lastMinutes = -1, lastSeconds = -1;

    private void pair(int at, int value) {
        timeChars[at] = (char) ('0' + value / 10);
        timeChars[at + 1] = (char) ('0' + value % 10);
    }

    private boolean stepSwap() {
        if (swapStart < 0L) {
            return false;
        }
        float p = (SystemClock.uptimeMillis() - swapStart) / (float) SWAP_MS;
        if (p >= 1f) {
            swapStart = -1L;
            swapProgress = 1f;
            prevMode = mode;
            return false;
        }
        swapProgress = 1f - (1f - p) * (1f - p);
        return true;
    }

    private boolean stepAbout() {
        if (aboutStart < 0L) {
            return false;
        }
        float p = (SystemClock.uptimeMillis() - aboutStart) / (float) ABOUT_MS;
        if (p >= 1f) {
            aboutStart = -1L;
            aboutProgress = aboutTo;
            return false;
        }
        float eased = 1f - (1f - p) * (1f - p) * (1f - p);
        aboutProgress = aboutFrom + (aboutTo - aboutFrom) * eased;
        return true;
    }

    private void toggleAbout() {
        aboutFrom = aboutProgress;
        aboutTo = aboutTo > 0.5f ? 0f : 1f;
        aboutStart = SystemClock.uptimeMillis();
        wake();
    }

    // ---------------------------------------------------------------- colour

    private void refreshPalette() {
        pageBackground = night ? Color.BLACK : Color.WHITE;
        hexColour = 0xFF000000 | Integer.parseInt(hex, 16);
        hexLabel = "#" + hex;
        pillBorder = night ? 0x80000000 : 0xCCFFFFFF;
        // The site draws the clock in the page colour; close to midnight that colour
        // reaches the card colour, so use the other extreme instead of vanishing.
        cardInk = contrast(pageBackground, hexColour) >= 2.1f
                ? pageBackground
                : (night ? Color.WHITE : Color.BLACK);
        ink = readable(hexColour, pageBackground);
    }

    private void storeMode() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(PREF_MODE, mode).apply();
    }

    static float luminance(int colour) {
        return 0.2126f * channel(Color.red(colour))
                + 0.7152f * channel(Color.green(colour))
                + 0.0722f * channel(Color.blue(colour));
    }

    private static float channel(int value) {
        float s = value / 255f;
        return s <= 0.04045f ? s / 12.92f : (float) Math.pow((s + 0.055f) / 1.055f, 2.4);
    }

    static float contrast(int a, int b) {
        float la = luminance(a);
        float lb = luminance(b);
        return (Math.max(la, lb) + 0.05f) / (Math.min(la, lb) + 0.05f);
    }

    /** Keeps the hue but pushes the colour off the background until it can be read. */
    static int readable(int foreground, int background) {
        if (contrast(foreground, background) >= 2.1f) {
            return foreground;
        }
        float[] hsv = new float[3];
        Color.colorToHSV(foreground, hsv);
        boolean lighten = luminance(background) < 0.5f;
        for (int i = 0; i < 24; i++) {
            float before = hsv[2];
            hsv[2] = clamp(before + (lighten ? 0.04f : -0.04f), 0f, 1f);
            foreground = Color.HSVToColor(hsv);
            if (hsv[2] == before || contrast(foreground, background) >= 2.1f) {
                return foreground;
            }
        }
        return foreground;
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : (value > max ? max : value);
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        layout();
    }

    private void layout() {
        float w = getWidth();
        float h = getHeight();

        wide = w > dp(WIDE_BREAKPOINT_DP);
        float outer = clamp(dp(10f), w * 0.0189f, dp(22f));
        headerPad = wide ? dp(28f) : dp(20f);
        infoSize = dp(40f);
        pillHeight = dp(48f);
        pillGap = wide ? dp(24f) : dp(16f);
        pillPadH = dp(13f);
        pillBottom = Math.max(h * 0.0575f, dp(14f));
        brandTracking = dp(4.5f);
        pillTracking = dp(3f);
        aboutPad = wide ? dp(34f) : dp(20f);
        aboutGap = wide ? dp(24f) : dp(16f);

        // The colour itself runs to every edge of the screen; these four bound only the
        // content, so nothing important sits under a status bar, a nav bar or a cutout.
        contentLeft = outer + safeL;
        contentTop = outer + safeT;
        contentRight = w - outer - safeR;
        contentBottom = h - outer - safeB;
        contentRect.set(contentLeft, contentTop, contentRight, contentBottom);
        float contentW = contentRight - contentLeft;
        float contentH = contentBottom - contentTop;

        brandPaint.setTextSize(dp(16f));
        pillPaint.setTextSize(dp(14f));
        brandPaint.getFontMetrics(fm);
        brandMid = (fm.ascent + fm.descent) / 2f;
        pillPaint.getFontMetrics(fm);
        pillMid = (fm.ascent + fm.descent) / 2f;

        // 17.5vmin, then reduced until the widest readout fits inside the card.
        readoutSize = Math.min(w, h) * 0.175f;
        readoutPaint.setTextSize(100f);
        float widthAt100 = trackedWidth(readoutPaint, "00:00:00", -2.5f);
        readoutSize = Math.min(readoutSize, (contentW - headerPad * 2f) / widthAt100 * 100f);

        float bandTop = contentTop + headerPad * 2f + infoSize;
        float bandBottom = contentBottom - pillBottom - pillHeight;
        readoutSize = Math.min(readoutSize, Math.max(bandBottom - bandTop, dp(24f)) * 0.55f);
        readoutPaint.setTextSize(readoutSize);
        readoutBaseline = (bandTop + bandBottom) / 2f
                - (readoutPaint.ascent() + readoutPaint.descent()) / 2f;
        readoutRect.set(contentLeft, bandTop, contentRight, bandBottom);

        float timePillW = pillWidth(timeLabel);
        float colourPillW = pillWidth(colourLabel);
        float pillsLeft = (w - (timePillW + colourPillW + pillGap)) / 2f;
        float pillTop = contentBottom - pillBottom - pillHeight;
        timeRect.set(pillsLeft, pillTop, pillsLeft + timePillW, pillTop + pillHeight);
        colourRect.set(timeRect.right + pillGap, pillTop, timeRect.right + pillGap + colourPillW, pillTop + pillHeight);

        infoRect.set(contentRight - headerPad - infoSize, contentTop + headerPad,
                contentRight - headerPad, contentTop + headerPad + infoSize);

        double radians = Math.toRadians(205);
        float dx = (float) Math.sin(radians);
        float dy = (float) -Math.cos(radians);
        // The wash now crosses the whole screen, so it is measured against the view, not the content.
        float span = (Math.abs(w * dx) + Math.abs(h * dy)) / 2f;
        overlayPaint.setShader(new LinearGradient(w / 2f - dx * span, h / 2f - dy * span,
                w / 2f + dx * span, h / 2f + dy * span,
                new int[]{0x12000000, 0x00000000}, new float[]{0.02f, 0.6f}, Shader.TileMode.CLAMP));

        if (night) {
            pillShader = new LinearGradient(0f, pillTop + pillHeight, 0f, pillTop,
                    new int[]{0xFF000000, 0xE0000000, 0x99000000},
                    new float[]{0.2f, 0.35f, 1f}, Shader.TileMode.CLAMP);
        } else {
            pillShader = new LinearGradient(0f, pillTop + pillHeight, 0f, pillTop,
                    new int[]{0xBFFFFFFF, 0xE0FFFFFF, 0xFFFFFFFF},
                    new float[]{0f, 0.35f, 0.8f}, Shader.TileMode.CLAMP);
        }
        aboutWidth = (int) Math.max((wide ? shrink() : contentW) - aboutPad * 2f, dp(150f));
        float aboutTop = wide ? contentTop + aboutPad : safeT + dp(ABOUT_TOP_DP) + aboutPad;
        float aboutRoom = contentBottom - aboutPad - aboutTop;
        float scale = 1f;
        // A short landscape panel cannot hold the copy at its preferred size, so the whole block
        // scales down together until it fits instead of running off the bottom of the screen.
        for (int attempt = 0; attempt < 4; attempt++) {
            layoutAboutText(scale);
            float need = aboutBlockHeight();
            if (need <= 0f || need <= aboutRoom) {
                break;
            }
            scale *= aboutRoom / need;
        }
    }

    /** Heading, body, captions and credit scale together so the block keeps its proportions. */
    private void layoutAboutText(float scale) {
        float shortSide = Math.min(getWidth(), getHeight());
        bodyPaint.setTextSize(Math.min(dp(24f), Math.max(dp(16f), shortSide * 0.055f)) * scale);
        captionPaint.setTextSize(dp(16f) * scale);
        creditPaint.setTextSize(dp(12f) * scale);
        headingPaint.setTextSize(dp(20f) * scale);
        headingTracking = dp(6.25f) * scale;
        // The heading is one tracked line, so on a narrow phone it has to shrink some more to
        // stay inside the panel rather than run off the edge.
        float headingWidth = trackedWidth(headingPaint, headingText, headingTracking);
        if (headingWidth > aboutWidth) {
            float fit = aboutWidth / headingWidth;
            headingTracking *= fit;
            headingPaint.setTextSize(headingPaint.getTextSize() * fit);
        }
        bodyLines = wrap(bodyPaint, bodyText, aboutWidth);
        fontLines = wrap(captionPaint, fontText, aboutWidth);
        madeLines = wrap(captionPaint, madeText, aboutWidth);
    }

    /** Height the whole block needs, walked in the same order as drawAbout lays it out. */
    private float aboutBlockHeight() {
        float height = headingPaint.getTextSize() * 1.7f + aboutGap;
        height += blockHeight(bodyLines, bodyPaint, 1.45f, fm) + aboutGap;
        height += blockHeight(fontLines, captionPaint, 1.4f, fm) + aboutGap;
        height += blockHeight(madeLines, captionPaint, 1.4f, fm);
        return height + creditPaint.getTextSize() * 1.6f;
    }

    /**
     * Hand-rolled word wrap. StaticLayout would do this, but its alignment constants
     * were renamed after API 14 and the old ones are gone from the current stubs.
     */
    private static String[] wrap(TextPaint paint, String text, int width) {
        ArrayList<String> lines = new ArrayList<String>();
        int start = 0;
        int end = text.length();
        while (start < end) {
            int take = paint.breakText(text, start, end, true, width, null);
            if (take <= 0) {
                break;
            }
            int stop = start + take;
            if (stop < end && text.charAt(stop) != ' ') {
                int space = text.lastIndexOf(' ', stop);
                if (space > start) {
                    stop = space;
                }
            }
            lines.add(text.substring(start, stop).trim());
            start = stop;
            while (start < end && text.charAt(start) == ' ') {
                start++;
            }
        }
        return lines.toArray(new String[lines.size()]);
    }

    private static float blockHeight(String[] lines, Paint paint, float mult, Paint.FontMetrics metrics) {
        paint.getFontMetrics(metrics);
        return (metrics.descent - metrics.ascent) * (mult * (lines.length - 1) + 1f);
    }

    private float drawLines(Canvas canvas, String[] lines, float left, float top, TextPaint paint,
                            int colour, int alpha, float mult) {
        paint.setColor(colour);
        paint.setAlpha(alpha);
        paint.getFontMetrics(fm);
        float step = (fm.descent - fm.ascent) * mult;
        float baseline = top - fm.ascent;
        for (String line : lines) {
            canvas.drawText(line, left, baseline, paint);
            baseline += step;
        }
        return top + blockHeight(lines, paint, mult, fm);
    }

    private float shrink() {
        return clamp(dp(380f), getWidth() * 0.25f, dp(440f));
    }

    private float pillWidth(String label) {
        return pillPadH * 2f + dp(20f) + dp(10f) + trackedWidth(pillPaint, label, pillTracking);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(pageBackground);
        if (bodyLines == null) {
            return;
        }

        float open = aboutProgress;
        cardRect.set(0f, 0f, getWidth(), getHeight());
        if (open > 0f) {
            if (wide) {
                cardRect.right -= shrink() * open;
            } else {
                float bar = contentTop + dp(ABOUT_BAR_DP);
                cardRect.bottom = bar + (cardRect.bottom - bar) * (1f - open);
            }
        }

        int clip = canvas.save();
        canvas.clipRect(cardRect);
        canvas.drawColor(hexColour);
        canvas.drawRect(cardRect, overlayPaint);

        drawBrand(canvas, contentRect);
        drawInfo(canvas);

        float content = 1f - open;
        if (content > 0.01f) {
            drawReadout(canvas, content);
            drawPills(canvas, content);
        }
        canvas.restoreToCount(clip);

        if (open > 0.01f) {
            drawAbout(canvas, open);
        }
    }

    private void drawBrand(Canvas canvas, RectF rect) {
        float centerY = rect.top + headerPad + infoSize / 2f;
        brandPaint.setColor(pageBackground);
        brandPaint.setAlpha(255);
        float end = drawTracked(canvas, brandText, rect.left + headerPad + dp(4f),
                centerY - brandMid, brandPaint, brandTracking);

        float gap = wide ? dp(32f) : dp(20f);
        float lineLeft = end + gap;
        float lineRight = infoRect.left - gap;
        if (lineRight - lineLeft > dp(60f)) {
            strokePaint.setColor(pageBackground);
            strokePaint.setAlpha(102);
            strokePaint.setStrokeWidth(dp(1f));
            canvas.drawLine(lineLeft, centerY + dp(1f), lineRight, centerY + dp(1f), strokePaint);
        }
    }

    private void drawInfo(Canvas canvas) {
        float cx = infoRect.centerX();
        float cy = infoRect.centerY();
        solidPaint.setShader(null);
        // setColor carries the alpha, so this needs no setAlpha of its own: the disc is meant to
        // stay a wash over the card, which is what keeps the white "i" readable.
        solidPaint.setColor(night ? 0x1FFFFFFF : 0x28FFFFFF);
        canvas.drawCircle(cx, cy, infoSize / 2f, solidPaint);

        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setStrokeCap(Paint.Cap.SQUARE);
        strokePaint.setColor(cardInk);
        strokePaint.setAlpha(255);

        int layer = canvas.save();
        canvas.rotate(-360f * aboutProgress, cx, cy);
        if (aboutProgress < 0.5f) {
            float r = infoSize * 0.18f;
            strokePaint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(cx, cy - r, r * 0.3f, strokePaint);
            strokePaint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(cx, cy - r * 0.15f, cx, cy + r * 1.05f, strokePaint);
        } else {
            float r = infoSize * 0.16f;
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokePaint);
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, strokePaint);
        }
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.restoreToCount(layer);
    }

    private void drawReadout(Canvas canvas, float contentAlpha) {
        float cx = (contentLeft + contentRight) / 2f;
        if (prevMode != mode) {
            drawString(canvas, prevMode, cx, 1f - 0.15f * swapProgress, (1f - swapProgress) * contentAlpha);
        }
        drawString(canvas, mode, cx, 0.85f + 0.15f * swapProgress, swapProgress * contentAlpha);
    }

    private void drawString(Canvas canvas, int which, float cx, float scale, float alpha) {
        if (alpha <= 0.01f) {
            return;
        }
        boolean time = which == MODE_TIME;
        String text = time ? timeString : hexLabel;
        float tracking = time ? -readoutSize * 0.025f : readoutSize * 0.036f;
        readoutPaint.setColor(cardInk);
        readoutPaint.setAlpha(255);
        readoutPaint.setShadowLayer(dp(6f), 0f, dp(1.5f), night ? 0x0A000000 : 0x14000000);

        int layer = canvas.save();
        canvas.scale(scale, scale, cx, readoutBaseline);
        readoutPaint.setAlpha(Math.round(255 * alpha));
        drawTrackedCentered(canvas, text, cx, readoutBaseline, readoutPaint, tracking);
        canvas.restoreToCount(layer);

        readoutPaint.setAlpha(255);
        readoutPaint.clearShadowLayer();
    }

    private void drawPills(Canvas canvas, float contentAlpha) {
        float timeActive = activeAmount(MODE_TIME);
        drawPill(canvas, timeRect, timeLabel, HIT_TIME, true, timeActive, contentAlpha);
        drawPill(canvas, colourRect, colourLabel, HIT_COLOUR, false, 1f - timeActive, contentAlpha);
    }

    private float activeAmount(int pillMode) {
        if (mode == pillMode) {
            return swapProgress;
        }
        return prevMode == pillMode ? 1f - swapProgress : 0f;
    }

    private void drawPill(Canvas canvas, RectF rect, String label, int hit, boolean clockIcon,
                          float active, float contentAlpha) {
        float radius = rect.height() / 2f;
        shape.reset();
        shape.addRoundRect(rect, radius, radius, Path.Direction.CW);

        if (active > 0.01f) {
            solidPaint.setShader(pillShader);
            solidPaint.setAlpha(Math.round(255 * active));
            canvas.drawPath(shape, solidPaint);
            solidPaint.setShader(null);
        }

        strokePaint.setColor(pillBorder);
        strokePaint.setAlpha(Math.round((pressed == hit ? 255 : 204) * contentAlpha));
        strokePaint.setStrokeWidth(dp(1f));
        canvas.drawPath(shape, strokePaint);

        int live = blend(pageBackground, ink, active);
        strokePaint.setColor(live);
        strokePaint.setAlpha(Math.round(255 * contentAlpha));
        strokePaint.setStrokeWidth(dp(1.5f));
        float ix = rect.left + pillPadH + dp(10f);
        float iy = rect.centerY();
        if (clockIcon) {
            float r = dp(9.25f);
            canvas.drawCircle(ix, iy, r, strokePaint);
            canvas.drawLine(ix, iy - r * 0.55f, ix, iy, strokePaint);
            canvas.drawLine(ix, iy, ix + r * 0.48f, iy + r * 0.28f, strokePaint);
        } else {
            float r = dp(9f);
            float inner = dp(6f);
            canvas.drawLine(ix - inner, iy - r, ix - inner, iy + r, strokePaint);
            canvas.drawLine(ix + inner, iy - r, ix + inner, iy + r, strokePaint);
            canvas.drawLine(ix - r, iy - inner, ix + r, iy - inner, strokePaint);
            canvas.drawLine(ix - r, iy + inner, ix + r, iy + inner, strokePaint);
        }

        pillPaint.setColor(live);
        pillPaint.setAlpha(Math.round(255 * contentAlpha));
        drawTracked(canvas, label, rect.left + pillPadH + dp(20f) + dp(10f),
                rect.centerY() - pillMid, pillPaint, pillTracking);
    }

    private static int blend(int from, int to, float amount) {
        if (amount <= 0f) {
            return from;
        }
        if (amount >= 1f) {
            return to;
        }
        return Color.argb(255,
                Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * amount),
                Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * amount),
                Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * amount));
    }

    private void drawAbout(Canvas canvas, float open) {
        int alpha = Math.round(255 * open);
        float left, top, right, bottom;
        if (wide) {
            right = getWidth() - aboutPad;
            left = getWidth() - shrink() + aboutPad;
            top = contentTop + aboutPad;
            bottom = contentBottom - aboutPad;
        } else {
            left = contentLeft + aboutPad;
            right = contentRight - aboutPad;
            top = safeT + dp(ABOUT_TOP_DP) + aboutPad;
            bottom = contentBottom - aboutPad;
        }

        headingPaint.setColor(ink);
        headingPaint.setAlpha(alpha);
        float y = top + headingPaint.getTextSize();
        drawTracked(canvas, headingText, left, y, headingPaint, headingTracking);
        y += headingPaint.getTextSize() * 0.7f + aboutGap;

        y = drawLines(canvas, bodyLines, left, y, bodyPaint, ink, alpha, 1.45f);
        y += aboutGap;
        float fontTop = y;
        y = drawLines(canvas, fontLines, left, y, captionPaint, ink, alpha, 1.4f);
        fontRect.set(left, fontTop, right, y);
        y = drawLines(canvas, madeLines, left, y + aboutGap, captionPaint, ink, alpha, 1.4f);

        creditPaint.setColor(ink);
        creditPaint.setAlpha(alpha);
        float baseline = Math.max(bottom - creditPaint.getTextSize(), y + creditPaint.getTextSize() * 1.6f);
        float end = drawTracked(canvas, creditText, left, baseline, creditPaint, dp(3.5f));
        float ruleY = baseline - creditPaint.getTextSize() * 0.35f;
        strokePaint.setColor(ink);
        strokePaint.setAlpha(alpha);
        strokePaint.setStrokeWidth(dp(1.25f));
        if (end + dp(24f) < right) {
            canvas.drawLine(end + dp(16f), ruleY, right, ruleY, strokePaint);
            canvas.drawLine(right - dp(6f), ruleY - dp(4f), right, ruleY, strokePaint);
            canvas.drawLine(right, ruleY, right - dp(6f), ruleY + dp(4f), strokePaint);
        }
        creditRect.set(left, baseline - creditPaint.getTextSize() * 1.5f, right, baseline + dp(8f));
    }

    private float drawTrackedCentered(Canvas canvas, String text, float centerX, float baseline,
                                      Paint paint, float tracking) {
        float width = trackedWidth(paint, text, tracking);
        return drawTracked(canvas, text, centerX - width / 2f, baseline, paint, tracking);
    }

    /** Manual tracking, because Paint.setLetterSpacing only exists from API 21. */
    private float drawTracked(Canvas canvas, String text, float x, float baseline, Paint paint, float tracking) {
        float cursor = x;
        for (int i = 0; i < text.length(); i++) {
            float advance = paint.measureText(text, i, i + 1);
            canvas.drawText(text, i, i + 1, cursor, baseline, paint);
            cursor += advance + tracking;
        }
        return cursor - tracking;
    }

    private static float trackedWidth(Paint paint, String text, float tracking) {
        float width = tracking * (text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            width += paint.measureText(text, i, i + 1);
        }
        return width;
    }

    // ---------------------------------------------------------------- touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        int action = event.getActionMasked();

        if (action == MotionEvent.ACTION_DOWN) {
            pressed = hitTest(x, y);
            if (pressed != HIT_NONE) {
                invalidate();
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (pressed == HIT_NONE) {
                return false;
            }
            int now = hitTest(x, y);
            if (now != pressed) {
                pressed = now;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            int was = pressed;
            pressed = HIT_NONE;
            invalidate();
            if (action == MotionEvent.ACTION_UP && was != HIT_NONE && hitTest(x, y) == was) {
                performClick();
                handleTap(was);
            }
            return was != HIT_NONE;
        }
        return false;
    }

    private int hitTest(float x, float y) {
        if (infoRect.contains(x, y)) {
            return HIT_INFO;
        }
        if (aboutProgress > 0.5f) {
            if (creditRect.contains(x, y)) {
                return HIT_CREDIT;
            }
            return fontRect.contains(x, y) ? HIT_FONT : HIT_NONE;
        }
        if (timeRect.contains(x, y)) {
            return HIT_TIME;
        }
        if (colourRect.contains(x, y)) {
            return HIT_COLOUR;
        }
        return readoutRect.contains(x, y) ? HIT_READOUT : HIT_NONE;
    }

    private void handleTap(int hit) {
        switch (hit) {
            case HIT_INFO:
                toggleAbout();
                break;
            case HIT_TIME:
                setMode(MODE_TIME);
                break;
            case HIT_COLOUR:
                setMode(MODE_COLOUR);
                break;
            case HIT_READOUT:
                setMode(mode == MODE_TIME ? MODE_COLOUR : MODE_TIME);
                break;
            case HIT_CREDIT:
                openUrl(URL_DESIGNER);
                break;
            case HIT_FONT:
                openUrl(URL_FONT);
                break;
            default:
                break;
        }
    }

    private void openUrl(String url) {
        try {
            getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException noBrowser) {
            // Nothing to open a link with; the line stays decorative.
        }
    }
}
