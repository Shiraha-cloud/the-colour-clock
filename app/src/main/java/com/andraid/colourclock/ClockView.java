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
 * Geometry is metric-driven: clamp/vmin style sizes and an 800px wide-layout breakpoint.
 */
public class ClockView extends View {

    public static final int MODE_TIME = 0;
    public static final int MODE_COLOUR = 1;

    /** Where the colour comes from: the time as six hex digits, or the slow ring. */
    public static final int STYLE_CLOCK = 0;
    public static final int STYLE_LOOP = 1;

    static final String PREFS = "colour_clock";
    static final String PREF_MODE = "mode";
    static final String PREF_SCALE = "readout_scale";
    static final String PREF_STYLE = "style";
    static final String PREF_PERIOD = "period_minutes";

    /** The readout is drawn at 17.5vmin; this setting multiplies that before the fit clamps. */
    static final int SCALE_MIN = 40;
    static final int SCALE_MAX = 160;
    static final int SCALE_STEP = 5;
    static final int SCALE_DEFAULT = 100;

    /** One minute to a day, but only these eighteen, so the drag always lands on a round value. */
    static final int[] PERIOD_STOPS = {1, 2, 3, 5, 10, 15, 20, 30, 45, 60,
            90, 120, 180, 240, 360, 480, 720, 1440};
    static final int PERIOD_DEFAULT = 60;
    static final int PERIOD_STOP_DEFAULT = 9;

    /** The last hex digit steps every 62.5 ms, so a 60 ms poll never skips a colour.
     * The ring is continuous, and a minute of it is still 16 steps a second at this rate. */
    private static final long TICK_IDLE_MS = 60;
    private static final long TICK_ANIM_MS = 16;
    private static final long ABOUT_MS = 400;
    private static final long SWAP_MS = 450;
    private static final long SIZE_MS = 260;
    private static final long MENU_MS = 220;

    private static final int WIDE_BREAKPOINT_DP = 800;
    private static final int ABOUT_BAR_DP = 80;
    private static final int ABOUT_TOP_DP = 96;
    private static final int MENU_WIDTH_DP = 300;

    private static final int HIT_NONE = 0;
    private static final int HIT_INFO = 1;
    private static final int HIT_TIME = 2;
    private static final int HIT_COLOUR = 3;
    private static final int HIT_READOUT = 4;
    private static final int HIT_CREDIT = 5;
    private static final int HIT_FONT = 6;
    private static final int HIT_SIZE = 7;
    private static final int HIT_SLIDER = 8;
    private static final int HIT_MENU = 9;
    private static final int HIT_STYLE_CLOCK = 10;
    private static final int HIT_STYLE_LOOP = 11;
    private static final int HIT_PERIOD = 12;
    private static final int HIT_BEHIND_MENU = 13;

    private static final String URL_DESIGNER = "https://www.designwithjack.com";
    private static final String URL_FONT = "https://cotypefoundry.com/our-fonts/aeonik-mono/";
    // Chinese line-breaking rules: these marks may not open a line, nor these close one.
    static final String NO_LINE_START = "，。、；：！？）》」』】〉”’…—～·";
    static final String NO_LINE_END = "（《「【〈“’";

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
    private final TextPaint menuPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint sizePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint headingPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint captionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint creditPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);

    private final Path shape = new Path();
    private final RectF cardRect = new RectF();
    private final RectF contentRect = new RectF();
    private final RectF infoRect = new RectF();
    private final RectF sizeRect = new RectF();
    private final RectF menuDiscRect = new RectF();
    private final RectF menuCardRect = new RectF();
    private final RectF clockRowRect = new RectF();
    private final RectF loopRowRect = new RectF();
    private final RectF periodRect = new RectF();
    private final RectF sliderRect = new RectF();
    private final RectF timeRect = new RectF();
    private final RectF colourRect = new RectF();
    private final RectF creditRect = new RectF();
    private final RectF fontRect = new RectF();
    private final RectF readoutRect = new RectF();
    /** Reused by the menu slide so the 16 fps draw pass still allocates nothing. */
    private final RectF scratchRect = new RectF();
    private final char[] timeChars = "00:00:00".toCharArray();
    /** Reused so the 16 fps draw pass allocates nothing. */
    private final Paint.FontMetrics fm = new Paint.FontMetrics();

    private String[] bodyLines, fontLines, madeLines;

    private String brandText, timeLabel, colourLabel, headingText, bodyText, creditText, fontText, madeText;
    private String menuHeading, styleClockLabel, styleLoopLabel, periodLabel;

    private int mode = MODE_TIME;
    private int prevMode = MODE_TIME;
    private int pressed = HIT_NONE;
    private int scalePercent = SCALE_DEFAULT;
    private int style = STYLE_CLOCK;
    private int periodStop = PERIOD_STOP_DEFAULT;

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
    private final Panel aboutPanel = new Panel(ABOUT_MS);
    private final Panel sizePanel = new Panel(SIZE_MS);
    private final Panel menuPanel = new Panel(MENU_MS);
    private boolean draggingScale;
    private boolean draggingPeriod;

    private int safeL, safeT, safeR, safeB;

    private float headerPad, infoSize, pillHeight, pillGap, pillBottom, pillPadH;
    private float brandTracking, pillTracking, readoutSize, readoutBaseline;
    private float aboutPad, aboutGap, headingTracking;
    private float contentLeft, contentTop, contentRight, contentBottom;
    private float brandMid, pillMid;
    private float trackLeft, trackRight, trackY;
    private float periodLeft, periodRight, periodY;
    private float menuPad, menuHeadingY, periodLabelY;
    private int aboutWidth;
    private Shader pillShader;

    public ClockView(Context context) {
        super(context);
        setClickable(true);
        setFocusable(true);
        setContentDescription(context.getString(R.string.app_name));

        night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        scalePercent = prefs.getInt(PREF_SCALE, SCALE_DEFAULT);
        style = prefs.getInt(PREF_STYLE, STYLE_CLOCK);
        periodStop = stopFor(prefs.getInt(PREF_PERIOD, PERIOD_DEFAULT));

        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL);
        brandPaint.setTypeface(mono);
        readoutPaint.setTypeface(mono);
        pillPaint.setTypeface(mono);
        menuPaint.setTypeface(mono);
        labelPaint.setTypeface(mono);
        sizePaint.setTypeface(mono);
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
        menuHeading = context.getString(R.string.menu_heading).toUpperCase(Locale.ROOT);
        styleClockLabel = context.getString(R.string.style_colour_clock);
        styleLoopLabel = context.getString(R.string.style_jack_hughes);
        periodLabel = context.getString(R.string.menu_period).toUpperCase(Locale.ROOT);

        refreshPalette();
    }

    // ---------------------------------------------------------------- public state

    /** Startup path: no crossfade from the default mode, just show what was saved. */
    public void restoreMode(int savedMode) {
        mode = style == STYLE_LOOP ? MODE_TIME : savedMode;
        prevMode = mode;
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

    public int getStyle() {
        return style;
    }

    public void setStyle(int newStyle) {
        if (newStyle == style) {
            return;
        }
        style = newStyle;
        if (newStyle == STYLE_LOOP) {
            // The ring has no hex to show, so the readout goes back to the time and stays there.
            setMode(MODE_TIME);
        }
        storeStyle();
        readClock();
        invalidate();
    }

    /** Picking a source ends the menu; the track under it is the one thing worth watching. */
    private void setStyleAndClose(int newStyle) {
        setStyle(newStyle);
        menuPanel.setOpen(false);
        wake();
    }

    /** Minutes for one lap of the ring; only ever one of the eighteen stops. */
    public int getPeriodMinutes() {
        return PERIOD_STOPS[periodStop];
    }

    public void setPeriodMinutes(int minutes) {
        setPeriodStop(stopFor(minutes), true);
    }

    // Which panel owns the screen. The three are mutually exclusive, and a test cannot tell
    // them apart from pixels alone, because both the card and the about panel are page-coloured.
    public boolean isAboutOpen() {
        return aboutPanel.isOpen();
    }

    public boolean isSizeOpen() {
        return sizePanel.isOpen();
    }

    public boolean isMenuOpen() {
        return menuPanel.isOpen();
    }

    /** Readout size as a percentage of the base 17.5vmin; the fit clamps still have the last word. */
    public int getTextScale() {
        return scalePercent;
    }

    public void setTextScale(int percent) {
        setScale(percent, true);
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
        boolean animating = stepSwap() | aboutPanel.step() | sizePanel.step() | menuPanel.step();
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

        String next = style == STYLE_LOOP
                ? ColourLoop.hexAt(System.currentTimeMillis(), getPeriodMinutes())
                : ColourEngine.hex(hours, minutes, seconds, millis);
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

    /**
     * One panel's open/close slide. Cubic ease out, and the duration is the only thing
     * that distinguishes the three of them.
     */
    private static final class Panel {
        private final long durationMs;
        private long start = -1L;
        private float from, to, progress;

        Panel(long durationMs) {
            this.durationMs = durationMs;
        }

        boolean isOpen() {
            return to > 0.5f;
        }

        float value() {
            return progress;
        }

        void setOpen(boolean wantOpen) {
            if ((to > 0.5f) == wantOpen) {
                return;
            }
            from = progress;
            to = wantOpen ? 1f : 0f;
            start = SystemClock.uptimeMillis();
        }

        /** @return true while the slide is still moving, which is when the view needs 16 fps. */
        boolean step() {
            if (start < 0L) {
                return false;
            }
            float p = (SystemClock.uptimeMillis() - start) / (float) durationMs;
            if (p >= 1f) {
                start = -1L;
                progress = to;
                return false;
            }
            float eased = 1f - (1f - p) * (1f - p) * (1f - p);
            progress = from + (to - from) * eased;
            return true;
        }
    }

    /** Every panel takes the screen over, so opening one always puts the other two away. */
    private void togglePanel(int which) {
        Panel hit = which == HIT_INFO ? aboutPanel
                : which == HIT_SIZE ? sizePanel : menuPanel;
        boolean open = !hit.isOpen();
        aboutPanel.setOpen(which == HIT_INFO && open);
        sizePanel.setOpen(which == HIT_SIZE && open);
        menuPanel.setOpen(which == HIT_MENU && open);
        wake();
    }

    private void setScale(int percent, boolean persist) {
        int wanted = clamp(percent, SCALE_MIN, SCALE_MAX);
        if (wanted == scalePercent) {
            return;
        }
        scalePercent = wanted;
        if (persist) {
            storeScale();
        }
        layoutReadout();
        invalidate();
    }

    /** Drag position on the track, snapped so the value lands on whole steps instead of 97%. */
    private void applyScaleDrag(float x) {
        if (trackRight <= trackLeft) {
            return;
        }
        float ratio = clamp((x - trackLeft) / (trackRight - trackLeft), 0f, 1f);
        int steps = Math.round(ratio * (SCALE_MAX - SCALE_MIN) / SCALE_STEP);
        setScale(SCALE_MIN + steps * SCALE_STEP, false);
    }

    /**
     * The period track snaps to the eighteen stops rather than to the minutes they mean, so
     * the thumb sits evenly along it even though the values run from 1 to 1440.
     */
    private void applyPeriodDrag(float x) {
        if (periodRight <= periodLeft) {
            return;
        }
        float ratio = clamp((x - periodLeft) / (periodRight - periodLeft), 0f, 1f);
        setPeriodStop(Math.round(ratio * (PERIOD_STOPS.length - 1)), false);
    }

    private void setPeriodStop(int stop, boolean persist) {
        int wanted = clamp(stop, 0, PERIOD_STOPS.length - 1);
        if (wanted == periodStop) {
            return;
        }
        periodStop = wanted;
        if (persist) {
            storePeriod();
        }
        readClock();
        invalidate();
    }

    /** Minutes back to a stop, so a value saved by an older build still puts the thumb somewhere. */
    private static int stopFor(int minutes) {
        int best = PERIOD_STOP_DEFAULT;
        int closest = Integer.MAX_VALUE;
        for (int i = 0; i < PERIOD_STOPS.length; i++) {
            int gap = Math.abs(PERIOD_STOPS[i] - minutes);
            if (gap < closest) {
                closest = gap;
                best = i;
            }
        }
        return best;
    }

    /** Hours where they read better, minutes everywhere else: 90 stays "90 分钟". */
    private String periodText(int minutes) {
        if (minutes > 60 && minutes % 60 == 0) {
            return getContext().getString(R.string.period_hours, minutes / 60);
        }
        return getContext().getString(R.string.period_minutes, minutes);
    }

    // ---------------------------------------------------------------- colour

    private void refreshPalette() {
        pageBackground = night ? Color.BLACK : Color.WHITE;
        hexColour = 0xFF000000 | Integer.parseInt(hex, 16);
        hexLabel = "#" + hex;
        pillBorder = night ? 0x80000000 : 0xCCFFFFFF;
        // The clock is drawn in the page colour; close to midnight that colour
        // reaches the card colour, so use the other extreme instead of vanishing.
        cardInk = contrast(pageBackground, hexColour) >= 2.1f
                ? pageBackground
                : (night ? Color.WHITE : Color.BLACK);
        ink = readable(hexColour, pageBackground);
    }

    private void storeMode() {
        prefs().edit().putInt(PREF_MODE, mode).apply();
    }

    private void storeScale() {
        prefs().edit().putInt(PREF_SCALE, scalePercent).apply();
    }

    private void storeStyle() {
        prefs().edit().putInt(PREF_STYLE, style).apply();
    }

    private void storePeriod() {
        prefs().edit().putInt(PREF_PERIOD, getPeriodMinutes()).apply();
    }

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    private static int clamp(int value, int min, int max) {
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

        pillPaint.setTextSize(dp(14f));
        pillPaint.getFontMetrics(fm);
        pillMid = (fm.ascent + fm.descent) / 2f;

        float timePillW = pillWidth(timeLabel);
        float colourPillW = pillWidth(colourLabel);
        float pillsLeft = (w - (timePillW + colourPillW + pillGap)) / 2f;
        float pillTop = contentBottom - pillBottom - pillHeight;
        timeRect.set(pillsLeft, pillTop, pillsLeft + timePillW, pillTop + pillHeight);
        colourRect.set(timeRect.right + pillGap, pillTop, timeRect.right + pillGap + colourPillW, pillTop + pillHeight);

        infoRect.set(contentRight - headerPad - infoSize, contentTop + headerPad,
                contentRight - headerPad, contentTop + headerPad + infoSize);
        float iconGap = dp(12f);
        sizeRect.set(infoRect.left - iconGap - infoSize, infoRect.top,
                infoRect.left - iconGap, infoRect.bottom);
        menuDiscRect.set(sizeRect.left - iconGap - infoSize, sizeRect.top,
                sizeRect.left - iconGap, sizeRect.bottom);
        fitBrand();

        layoutMenu();
        layoutReadout();

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

    /**
     * Three discs plus their gaps are fixed, so the wordmark is the only thing in the header
     * that can give. It loses tracking first and size second, rather than hiding under a disc.
     */
    private void fitBrand() {
        float left = contentLeft + headerPad + dp(4f);
        float room = menuDiscRect.left - left - (wide ? dp(32f) : dp(20f));
        brandPaint.setTextSize(dp(16f));
        brandTracking = dp(4.5f);
        float width = trackedWidth(brandPaint, brandText, brandTracking);
        if (width > room) {
            brandTracking = Math.max(dp(1.5f),
                    brandTracking - (width - room) / (brandText.length() - 1));
            width = trackedWidth(brandPaint, brandText, brandTracking);
            if (width > room) {
                brandPaint.setTextSize(dp(16f) * room / width);
            }
        }
        brandPaint.getFontMetrics(fm);
        brandMid = (fm.ascent + fm.descent) / 2f;
    }

    /**
     * The menu hangs from its own disc instead of taking the screen over, so it is measured
     * against the header row. One rounded card holds the two colour sources and, under them,
     * the track that picks how long a lap of the ring lasts.
     */
    private void layoutMenu() {
        float cardWidth = Math.min(dp(MENU_WIDTH_DP), contentRight - contentLeft - headerPad * 2f);
        float pad = dp(18f);
        float row = dp(44f);
        menuPad = pad;
        menuCardRect.set(contentRight - headerPad - cardWidth, menuDiscRect.bottom + dp(12f),
                contentRight - headerPad, 0f);

        menuHeadingY = menuCardRect.top + pad + dp(10f);
        float y = menuHeadingY + dp(11f);
        clockRowRect.set(menuCardRect.left, y, menuCardRect.right, y + row);
        loopRowRect.set(menuCardRect.left, clockRowRect.bottom, menuCardRect.right,
                clockRowRect.bottom + row);

        y = loopRowRect.bottom + dp(8f);
        periodLabelY = y + dp(12f);
        periodLeft = menuCardRect.left + pad;
        periodRight = menuCardRect.right - pad;
        periodY = periodLabelY + dp(26f);
        // The touch strip is the visible block under the label, which is already taller than
        // the line itself, so the thumb does not need any slack beyond the card edge.
        periodRect.set(menuCardRect.left, periodLabelY + dp(4f), menuCardRect.right, periodY + dp(14f));
        menuCardRect.bottom = periodY + dp(14f);

        labelPaint.setTextSize(dp(13f));
        menuPaint.setTextSize(dp(16f));
    }

    /**
     * The readout and the size slider live in the same band, so they are laid out together: the
     * readout's 17.5vmin is multiplied by the saved scale, and the two clamps after that are what
     * keep the string from ever being cut by the screen edge or crowded by the pill row. The
     * slider geometry is recomputed here as well, because dragging must not re-wrap the copy.
     */
    private void layoutReadout() {
        float contentW = contentRight - contentLeft;
        float bandTop = contentTop + headerPad * 2f + infoSize;
        float bandBottom = contentBottom - pillBottom - pillHeight;

        readoutPaint.setTextSize(100f);
        float widthAt100 = trackedWidth(readoutPaint, "00:00:00", -2.5f);
        float fit = Math.min((contentW - headerPad * 2f) / widthAt100 * 100f,
                Math.max(bandBottom - bandTop, dp(24f)) * 0.55f);
        readoutSize = clamp(Math.min(getWidth(), getHeight()) * 0.175f * scalePercent / 100f,
                dp(16f), fit);
        readoutPaint.setTextSize(readoutSize);
        readoutBaseline = (bandTop + bandBottom) / 2f
                - (readoutPaint.ascent() + readoutPaint.descent()) / 2f;
        readoutRect.set(contentLeft, bandTop, contentRight, bandBottom);

        // Room at both ends so the thumb can sit on the very last tick without burying its A.
        float marker = dp(44f);
        trackLeft = contentLeft + headerPad + marker;
        trackRight = contentRight - headerPad - marker;
        trackY = bandBottom - pillBottom - dp(18f);
        // Less room asked for above the track than below: at the largest scale the readout
        // reaches down close to the strip, and its digits must stay tappable to switch modes.
        sliderRect.set(contentLeft, trackY - dp(20f), contentRight, trackY + dp(30f));
    }

    private float trackFor(int percent) {
        return trackLeft + (trackRight - trackLeft) * (percent - SCALE_MIN)
                / (float) (SCALE_MAX - SCALE_MIN);
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
    static String[] wrap(TextPaint paint, String text, int width) {
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
            stop = avoidLeadingMark(text, start, stop, end);
            lines.add(text.substring(start, stop).trim());
            start = stop;
            while (start < end && text.charAt(start) == ' ') {
                start++;
            }
        }
        return lines.toArray(new String[lines.size()]);
    }

    /**
     * CJK has no spaces to break on, so the plain fit would happily start a line with a full
     * stop. A mark that cannot open a line is pulled onto the previous one, and one that cannot
     * close it is pushed to the next, even though each costs a little width.
     */
    private static int avoidLeadingMark(String text, int start, int stop, int end) {
        if (stop < end && NO_LINE_START.indexOf(text.charAt(stop)) >= 0) {
            return stop + 1;
        }
        if (stop > start + 1 && NO_LINE_END.indexOf(text.charAt(stop - 1)) >= 0) {
            return stop - 1;
        }
        return stop;
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

        float open = aboutPanel.value();
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
        drawMenuButton(canvas);
        drawSizeButton(canvas);
        drawInfo(canvas);

        float content = 1f - open;
        if (content > 0.01f) {
            drawReadout(canvas, content);
            // The ring has no time-as-hex to choose between, so its row of pills goes away.
            if (style == STYLE_CLOCK) {
                drawPills(canvas, content);
            }
            if (sizePanel.value() > 0.01f) {
                drawSizeSlider(canvas, sizePanel.value() * content);
            }
        }
        canvas.restoreToCount(clip);

        if (open > 0.01f) {
            drawAbout(canvas, open);
        }
        if (menuPanel.value() > 0.01f) {
            drawMenu(canvas, menuPanel.value());
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
        float lineRight = menuDiscRect.left - gap;
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
        canvas.rotate(-360f * aboutPanel.value(), cx, cy);
        if (aboutPanel.value() < 0.5f) {
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

    /** The third header disc: same wash as its siblings, three lines that fold into a cross. */
    private void drawMenuButton(Canvas canvas) {
        float cx = menuDiscRect.centerX();
        float cy = menuDiscRect.centerY();
        int fill = (night ? 0x1F : 0x28) + Math.round(0x24 * menuPanel.value());
        solidPaint.setShader(null);
        solidPaint.setColor((fill << 24) | 0x00FFFFFF);
        canvas.drawCircle(cx, cy, infoSize / 2f, solidPaint);

        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setStrokeCap(Paint.Cap.SQUARE);
        strokePaint.setColor(cardInk);
        strokePaint.setAlpha(255);
        int layer = canvas.save();
        canvas.rotate(-360f * menuPanel.value(), cx, cy);
        float r = infoSize * 0.16f;
        if (menuPanel.value() < 0.5f) {
            for (int i = -1; i <= 1; i++) {
                float line = cy + i * r * 0.82f;
                canvas.drawLine(cx - r, line, cx + r, line, strokePaint);
            }
        } else {
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, strokePaint);
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, strokePaint);
        }
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
        canvas.restoreToCount(layer);
    }

    /**
     * The card slides down from its own disc and is opaque, because it floats over the clock
     * rather than pushing the clock away the way the about panel does.
     */
    private void drawMenu(Canvas canvas, float amount) {
        float dy = dp(8f) * (amount - 1f);
        int alpha = Math.round(255 * amount);
        float radius = dp(14f);
        scratchRect.set(menuCardRect.left, menuCardRect.top + dy,
                menuCardRect.right, menuCardRect.bottom + dy);

        shape.reset();
        shape.addRoundRect(scratchRect, radius, radius, Path.Direction.CW);
        solidPaint.setShader(null);
        solidPaint.setColor(pageBackground);
        solidPaint.setAlpha(alpha);
        canvas.drawPath(shape, solidPaint);
        strokePaint.setColor(night ? 0x33FFFFFF : 0x14000000);
        strokePaint.setAlpha(alpha);
        strokePaint.setStrokeWidth(dp(1f));
        canvas.drawPath(shape, strokePaint);

        float left = scratchRect.left + menuPad;
        labelPaint.setColor(ink);
        labelPaint.setAlpha(Math.round(120 * amount));
        drawTracked(canvas, menuHeading, left, menuHeadingY + dy, labelPaint, dp(3f));

        drawModeRow(canvas, clockRowRect, styleClockLabel, style == STYLE_CLOCK, HIT_STYLE_CLOCK,
                left, dy, amount);
        drawModeRow(canvas, loopRowRect, styleLoopLabel, style == STYLE_LOOP, HIT_STYLE_LOOP,
                left, dy, amount);
        // A period that is not in use still answers a drag, it just asks for less attention.
        drawPeriodTrack(canvas, dy, amount * (style == STYLE_LOOP ? 1f : 0.45f));
    }

    private void drawModeRow(Canvas canvas, RectF row, String label, boolean selected, int hit,
                             float textLeft, float dy, float amount) {
        if (pressed == hit) {
            solidPaint.setShader(null);
            solidPaint.setColor(night ? 0x1FFFFFFF : 0x0D000000);
            solidPaint.setAlpha(Math.round(255 * amount));
            canvas.drawRect(row.left, row.top + dy, row.right, row.bottom + dy, solidPaint);
        }
        float cy = row.centerY() + dy;
        float r = dp(6.5f);
        float marker = textLeft + r;
        strokePaint.setColor(ink);
        strokePaint.setAlpha(Math.round((selected ? 255 : 110) * amount));
        strokePaint.setStrokeWidth(dp(1.5f));
        canvas.drawCircle(marker, cy, r, strokePaint);
        if (selected) {
            solidPaint.setShader(null);
            solidPaint.setColor(ink);
            solidPaint.setAlpha(Math.round(255 * amount));
            canvas.drawCircle(marker, cy, r * 0.55f, solidPaint);
        }

        menuPaint.setColor(ink);
        menuPaint.setAlpha(Math.round((selected ? 255 : 165) * amount));
        canvas.drawText(label, textLeft + dp(26f), cy - (menuPaint.ascent() + menuPaint.descent()) / 2f,
                menuPaint);
    }

    private void drawPeriodTrack(Canvas canvas, float dy, float amount) {
        labelPaint.setColor(ink);
        labelPaint.setAlpha(Math.round(120 * amount));
        drawTracked(canvas, periodLabel, scratchRect.left + menuPad, periodLabelY + dy,
                labelPaint, dp(3f));

        String value = periodText(getPeriodMinutes());
        labelPaint.setAlpha(Math.round(210 * amount));
        canvas.drawText(value, scratchRect.right - menuPad - labelPaint.measureText(value),
                periodLabelY + dy, labelPaint);

        float thumb = trackForStop(periodStop);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(dp(2f));
        strokePaint.setColor(ink);
        strokePaint.setAlpha(Math.round(80 * amount));
        canvas.drawLine(periodLeft, periodY + dy, periodRight, periodY + dy, strokePaint);
        strokePaint.setAlpha(Math.round(190 * amount));
        canvas.drawLine(periodLeft, periodY + dy, thumb, periodY + dy, strokePaint);

        // A tick per stop, so it is visible that the thumb only ever comes to rest on eighteen.
        strokePaint.setStrokeCap(Paint.Cap.BUTT);
        strokePaint.setStrokeWidth(dp(1f));
        strokePaint.setAlpha(Math.round(70 * amount));
        for (int i = 0; i < PERIOD_STOPS.length; i++) {
            float x = trackForStop(i);
            canvas.drawLine(x, periodY + dy - dp(5f), x, periodY + dy + dp(5f), strokePaint);
        }

        solidPaint.setShader(null);
        solidPaint.setColor(ink);
        solidPaint.setAlpha(Math.round(255 * amount));
        canvas.drawCircle(thumb, periodY + dy, dp(9f), solidPaint);
    }

    private float trackForStop(int stop) {
        return periodLeft + (periodRight - periodLeft) * stop / (PERIOD_STOPS.length - 1f);
    }

    /** The size toggle is the info disc's sibling: same wash, same ink, an "A" instead of an "i". */
    private void drawSizeButton(Canvas canvas) {
        float cx = sizeRect.centerX();
        float cy = sizeRect.centerY();
        int fill = (night ? 0x1F : 0x28) + Math.round(0x24 * sizePanel.value());
        solidPaint.setShader(null);
        solidPaint.setColor((fill << 24) | 0x00FFFFFF);
        canvas.drawCircle(cx, cy, infoSize / 2f, solidPaint);
        drawGlyphCentred(canvas, "A", cx, cy, dp(17f), cardInk, 255);
    }

    private void drawSizeSlider(Canvas canvas, float amount) {
        float thumb = trackFor(scalePercent);

        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeWidth(dp(2f));
        strokePaint.setColor(cardInk);
        strokePaint.setAlpha(Math.round(80 * amount));
        canvas.drawLine(trackLeft, trackY, trackRight, trackY, strokePaint);
        strokePaint.setAlpha(Math.round(180 * amount));
        canvas.drawLine(trackLeft, trackY, thumb, trackY, strokePaint);

        // One tick every 20%, so the middle one marks the untouched 17.5vmin default.
        strokePaint.setStrokeWidth(dp(1.5f));
        strokePaint.setAlpha(Math.round(110 * amount));
        for (int percent = SCALE_MIN; percent <= SCALE_MAX; percent += 20) {
            float x = trackFor(percent);
            canvas.drawLine(x, trackY - dp(7f), x, trackY + dp(7f), strokePaint);
        }
        strokePaint.setStrokeCap(Paint.Cap.BUTT);

        solidPaint.setShader(null);
        solidPaint.setColor(cardInk);
        solidPaint.setAlpha(Math.round(255 * amount));
        canvas.drawCircle(thumb, trackY, dp(10f), solidPaint);

        // The two A are the legend; the number under the track is what the drag actually picked.
        drawGlyphCentred(canvas, "A", trackLeft - dp(24f), trackY, dp(11f), cardInk, Math.round(200 * amount));
        drawGlyphCentred(canvas, "A", trackRight + dp(24f), trackY, dp(19f), cardInk, Math.round(200 * amount));
        sizePaint.setTextSize(dp(13f));
        sizePaint.setColor(cardInk);
        sizePaint.setAlpha(Math.round(150 * amount));
        String label = scalePercent + "%";
        sizePaint.getFontMetrics(fm);
        canvas.drawText(label, (trackLeft + trackRight) / 2f - sizePaint.measureText(label) / 2f,
                trackY + dp(30f) - (fm.ascent + fm.descent) / 2f, sizePaint);
    }

    private void drawGlyphCentred(Canvas canvas, String text, float centerX, float centerY,
                                  float size, int colour, int alpha) {
        sizePaint.setTextSize(size);
        sizePaint.setColor(colour);
        sizePaint.setAlpha(alpha);
        sizePaint.getFontMetrics(fm);
        canvas.drawText(text, centerX - sizePaint.measureText(text) / 2f,
                centerY - (fm.ascent + fm.descent) / 2f, sizePaint);
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
                if (pressed == HIT_SLIDER) {
                    draggingScale = true;
                    applyScaleDrag(x);
                } else if (pressed == HIT_PERIOD) {
                    // Grabbing the track is the clearest possible way of asking for the ring.
                    setStyle(STYLE_LOOP);
                    draggingPeriod = true;
                    applyPeriodDrag(x);
                }
                invalidate();
                return true;
            }
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (draggingScale) {
                // The finger may leave the strip mid-drag; keep following it until it lifts.
                applyScaleDrag(x);
                invalidate();
                return true;
            }
            if (draggingPeriod) {
                applyPeriodDrag(x);
                invalidate();
                return true;
            }
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
            if (draggingScale || draggingPeriod) {
                // A drag persists once, on release, so the slider never writes prefs per frame.
                if (draggingScale) {
                    storeScale();
                } else {
                    storePeriod();
                }
                draggingScale = false;
                draggingPeriod = false;
                pressed = HIT_NONE;
                invalidate();
                return true;
            }
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
        if (sizeRect.contains(x, y)) {
            return HIT_SIZE;
        }
        if (menuDiscRect.contains(x, y)) {
            return HIT_MENU;
        }
        if (menuPanel.value() > 0.5f) {
            // The card floats above the clock, so it is asked first; anything it does not
            // cover closes it instead of reaching through to the readout underneath.
            if (clockRowRect.contains(x, y)) {
                return HIT_STYLE_CLOCK;
            }
            if (loopRowRect.contains(x, y)) {
                return HIT_STYLE_LOOP;
            }
            if (periodRect.contains(x, y)) {
                return HIT_PERIOD;
            }
            return HIT_BEHIND_MENU;
        }
        if (aboutPanel.value() > 0.5f) {
            if (creditRect.contains(x, y)) {
                return HIT_CREDIT;
            }
            return fontRect.contains(x, y) ? HIT_FONT : HIT_NONE;
        }
        // The strip sits inside the readout band, so it has to be asked first or the tap
        // would switch modes instead of dragging.
        if (sizePanel.value() > 0.5f && sliderRect.contains(x, y)) {
            return HIT_SLIDER;
        }
        if (style == STYLE_LOOP) {
            return HIT_NONE;
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
            case HIT_SIZE:
            case HIT_MENU:
                togglePanel(hit);
                break;
            case HIT_BEHIND_MENU:
                menuPanel.setOpen(false);
                wake();
                break;
            case HIT_STYLE_CLOCK:
                setStyleAndClose(STYLE_CLOCK);
                break;
            case HIT_STYLE_LOOP:
                setStyleAndClose(STYLE_LOOP);
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
