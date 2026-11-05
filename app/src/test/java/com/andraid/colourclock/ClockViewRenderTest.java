package com.andraid.colourclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Calendar;
import java.util.concurrent.TimeUnit;

/**
 * Draws the real ClockView off-screen so the geometry and the palette pipeline can be checked
 * without a handset. PNGs land in build/render-check for eyeballing.
 *
 * Robolectric shadows the wall clock, so the view and these tests read the same instant. The
 * expected colour is therefore derived from that instant rather than hard-coded to one time.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
// Legacy mode records canvas calls but never writes pixels, so nothing here would be verifiable.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClockViewRenderTest {

    private static final int PORTRAIT_W = 1080;
    private static final int PORTRAIT_H = 2340;
    private static final int LANDSCAPE_W = 2340;
    private static final int LANDSCAPE_H = 1080;
    private static final float DENSITY = 2.625f; // the 420dpi qualifier used below

    private static final int SHADE = 24;  // slack for the -155deg overlay, which is 7% black
    private static final int GLYPH = 60;  // distance from the card fill that counts as ink

    @Rule
    public TestName name = new TestName();

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void portraitTimeMode() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        f.draw();
        assertColourReachesEveryEdge(f);
        assertCardFilled(f);
        assertReadoutNotClipped(f);
        save(f.bitmap);
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void colourModeAfterPillTap() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        // The pills are centred as a pair, so the colour pill begins half a gap right of the
        // middle. Tapping dead centre would land in the gap and hit nothing.
        tap(f.view, PORTRAIT_W / 2f + dp(20f), pillMidY(PORTRAIT_W, PORTRAIT_H, 0));
        f.settle();
        assertEquals(ClockView.MODE_COLOUR, f.view.getMode());
        f.draw();
        assertColourReachesEveryEdge(f);
        assertCardFilled(f);
        assertReadoutNotClipped(f);
        save(f.bitmap);
    }

    /**
     * The handset reported a white frame because the status bar inset was added to the card, not
     * just to the content. The colour must still run to every edge with an inset present, while
     * the header moves down out of the band.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void statusInsetPushesContentNotColour() throws Exception {
        int inset = Math.round(dp(36f));
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, inset);
        f.draw();
        assertColourReachesEveryEdge(f);
        int full = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);

        // Dead in the old header position there is now nothing to hit, so the panel stays shut.
        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        int stayed = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("header did not move with the inset: " + stayed + " vs " + full,
                Math.abs(stayed - full) < full / 20);

        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, inset));
        f.settle();
        f.draw();
        int opened = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("inset header opened the panel: " + full + " -> " + opened, opened < full / 4);
        save(f.bitmap);
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void aboutPanelShrinksCardToABar() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        f.draw();
        int card = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);
        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        int after = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("card collapses to the 80dp bar: " + card + " -> " + after, after < card / 4);

        // Below the bar the page shows through again, carrying the about copy in the ink colour.
        int below = Math.round(dp(320f));
        int samples = ((PORTRAIT_H - below) / 4) * (PORTRAIT_W / 4);
        int ink = countNear(f.bitmap, f.ink, 0, below, PORTRAIT_W, PORTRAIT_H, SHADE);
        int page = countEqual(f.bitmap, 0xFFFFFFFF, 0, below, PORTRAIT_W, PORTRAIT_H);
        assertTrue("page background returns below the bar (" + page + "/" + samples + ")",
                page > samples * 3 / 4);
        assertTrue("about copy is drawn (" + ink + " ink pixels)", ink > 200);
        assertTrue("ink stays off the page colour", f.ink != 0xFFFFFFFF);
        assertHeadingClearOfEdges(f, Math.round(dp(110f)), Math.round(dp(170f)));
        save(f.bitmap);
    }

    @Test
    @Config(qualifiers = "w914dp-h411dp-land-notnight-420dpi")
    public void landscapeKeepsReadoutInside() throws Exception {
        Fixture f = new Fixture(LANDSCAPE_W, LANDSCAPE_H, 0xFFFFFFFF, 0);
        f.draw();
        assertColourReachesEveryEdge(f);
        assertCardFilled(f);
        assertReadoutNotClipped(f);
        save(f.bitmap);
    }

    @Test
    @Config(qualifiers = "w411dp-h914dp-port-night-420dpi")
    public void nightModeInvertsPage() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFF000000, 0);
        f.draw();
        assertColourReachesEveryEdge(f);
        assertCardFilled(f);
        assertReadoutNotClipped(f);
        assertTrue("night ink stays readable on the hex", f.ink != f.page);

        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        int below = Math.round(dp(320f));
        int page = countEqual(f.bitmap, 0xFF000000, 0, below, PORTRAIT_W, PORTRAIT_H);
        int samples = ((PORTRAIT_H - below) / 4) * (PORTRAIT_W / 4);
        assertTrue("night page is black below the bar (" + page + "/" + samples + ")",
                page > samples * 3 / 4);
        save(f.bitmap);
    }

    /**
     * In wide layout the copy lives in the strip the colour just vacated, so the strip width
     * decides the wrap width. Too narrow and the text runs off the bottom of the panel.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-notnight-420dpi")
    public void landscapeAboutFillsTheRightStrip() throws Exception {
        Fixture f = new Fixture(LANDSCAPE_W, LANDSCAPE_H, 0xFFFFFFFF, 0);
        tap(f.view, infoCentreX(LANDSCAPE_W, true), infoCentreY(LANDSCAPE_W, 0));
        f.settle();
        f.draw();

        int stripLeft = Math.round(LANDSCAPE_W - shrink(LANDSCAPE_W));
        int samples = (stripLeft / 4) * (LANDSCAPE_H / 4);
        int colour = countNear(f.bitmap, f.colour, 0, 0, stripLeft, LANDSCAPE_H, SHADE);
        assertTrue("colour keeps the left of the screen: " + colour + "/" + samples,
                colour > samples * 8 / 10);
        int pageSamples = ((LANDSCAPE_W - stripLeft) / 4) * (LANDSCAPE_H / 4);
        int page = countEqual(f.bitmap, 0xFFFFFFFF, stripLeft, 0, LANDSCAPE_W, LANDSCAPE_H);
        assertTrue("strip returns to the page: " + page + "/" + pageSamples,
                page > pageSamples * 3 / 4);

        int ink = countNear(f.bitmap, f.ink, stripLeft, 0, LANDSCAPE_W, LANDSCAPE_H, SHADE);
        assertTrue("about copy is drawn (" + ink + " ink pixels)", ink > 200);
        int last = lastInkRow(f, stripLeft);
        int limit = LANDSCAPE_H - Math.round(outerPad(LANDSCAPE_W));
        assertTrue("copy overruns the panel bottom: last row " + last + " of " + limit,
                last > 0 && last < limit);
        save(f.bitmap);
    }

    /**
     * The about copy is Chinese now, and CJK has no spaces to break on. A plain fit happily
     * starts a line with a full stop, which reads as broken typography on any handset.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void chineseCopyRespectsLineBreakRules() throws Exception {
        TextPaint paint = new TextPaint();
        paint.setTextSize(dp(22f));
        String body = RuntimeEnvironment.getApplication().getString(R.string.about_body);
        String[] lines = ClockView.wrap(paint, body, Math.round(dp(300f)));
        assertTrue("copy actually wrapped (" + lines.length + " lines)", lines.length > 3);
        for (String line : lines) {
            assertFalse("line opens with a closing mark: " + line,
                    ClockView.NO_LINE_START.indexOf(line.charAt(0)) >= 0);
            assertFalse("line ends with an opening mark: " + line,
                    ClockView.NO_LINE_END.indexOf(line.charAt(line.length() - 1)) >= 0);
            assertTrue("empty line: [" + line + "]", line.length() > 0);
        }
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            joined.append(line);
        }
        // Line-break spaces disappear by design; nothing else may.
        assertEquals("no character lost while wrapping", body.replace(" ", ""),
                joined.toString().replace(" ", ""));
    }

    // ---------------------------------------------------------------- fixtures

    private final class Fixture {
        final ClockView view;
        final Bitmap bitmap;
        final Canvas canvas;
        final int page;
        final int colour;
        final int ink;

        Fixture(int width, int height, int page) {
            this(width, height, page, 0);
        }

        Fixture(int width, int height, int page, int safeTop) {
            Context context = RuntimeEnvironment.getApplication();
            view = new ClockView(context);
            view.setSafeInsets(0, safeTop, 0, 0);
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            view.startTicking();
            settle();
            this.page = page;
            Calendar now = Calendar.getInstance();
            now.setTimeInMillis(System.currentTimeMillis());
            String hex = ColourEngine.hex(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND), now.get(Calendar.MILLISECOND));
            colour = 0xFF000000 | Integer.parseInt(hex, 16);
            ink = ClockView.readable(colour, page);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(bitmap);
        }

        void draw() {
            view.draw(canvas);
        }

        int pixel(int x, int y) {
            return bitmap.getPixel(x, y);
        }

        void settle() {
            // Long enough for the 400ms panel and 450ms swap animations to finish.
            for (int i = 0; i < 40; i++) {
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
            }
        }
    }

    // ---------------------------------------------------------------- geometry

    /** Content is padded this far inside the view; mirrors the site's clamp(1vw, 10px..22px). */
    private static float outerPad(int width) {
        return Math.min(dp(22f), Math.max(dp(10f), width * 0.0189f));
    }

    private static float pillMidY(int width, int height, int safeTop) {
        float contentBottom = height - outerPad(width) - safeTop;
        float pillBottom = Math.max(height * 0.0575f, dp(14f));
        return contentBottom - pillBottom - dp(48f) / 2f;
    }

    private static float infoCentreX(int width) {
        return infoCentreX(width, false);
    }

    private static float infoCentreX(int width, boolean wide) {
        return width - outerPad(width) - (wide ? dp(28f) : dp(20f)) - dp(40f) / 2f;
    }

    private static float infoCentreY(int width, int safeTop) {
        return outerPad(width) + safeTop + dp(20f) + dp(40f) / 2f;
    }

    /** Mirrors ClockView.shrink(): how much of the width the colour gives up to the panel. */
    private static float shrink(int width) {
        return Math.min(dp(440f), Math.max(dp(380f), width * 0.25f));
    }

    // ---------------------------------------------------------------- pixel checks

    /**
     * The colour is the screen: no page-colour frame may survive at any edge, which is exactly
     * what the handset screenshot showed.
     */
    private static void assertColourReachesEveryEdge(Fixture f) {
        int width = f.view.getWidth();
        int height = f.view.getHeight();
        int[][] points = {
                {4, 4}, {width - 5, 4}, {4, height - 5}, {width - 5, height - 5},
                {width / 2, 4}, {width / 2, height - 5}, {4, height / 2}, {width - 5, height / 2}
        };
        for (int[] p : points) {
            assertTrue("edge point " + p[0] + "," + p[1] + " is page colour, not the clock colour",
                    close(f.pixel(p[0], p[1]), f.colour, SHADE));
        }
    }

    /** The card fill has to dominate the view, otherwise the palette never reached the canvas. */
    private static void assertCardFilled(Fixture f) {
        int width = f.view.getWidth();
        int height = f.view.getHeight();
        int edge = Math.round(dp(60f));
        int samples = ((height - 2 * edge) / 4) * ((width - 2 * edge) / 4);
        int matched = countNear(f.bitmap, f.colour, edge, edge, width - edge, height - edge, SHADE);
        assertTrue("card fill covers the card: " + matched + "/" + samples, matched > samples * 8 / 10);
    }

    /**
     * The readout is one tracked line, so an over-long string is cut rather than drawn past the
     * content box. A hard cut shows up as ink running flush with the edge, which is what the
     * fit-to-width clamp exists to avoid.
     */
    private static void assertReadoutNotClipped(Fixture f) {
        int width = f.view.getWidth();
        int height = f.view.getHeight();
        float outer = outerPad(width);
        int left0 = Math.round(outer) + 3;
        int right0 = Math.round(width - outer) - 3;
        int bandTop = Math.round(height * 0.30f);
        int bandBottom = Math.round(height * 0.62f);
        int left = width;
        int right = -1;
        int count = 0;
        for (int y = bandTop; y < bandBottom; y += 2) {
            for (int x = left0; x < right0; x++) {
                if (!close(f.pixel(x, y), f.colour, GLYPH)) {
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                    count++;
                }
            }
        }
        assertTrue("readout ink found (" + count + " px)", count > 200);
        assertTrue("right edge " + right + " clear of the screen", right < width - outer - dp(8f));
        assertTrue("left edge " + left + " clear of the screen", left > outer + dp(8f));
        assertTrue("readout stays centred: " + left + ".." + right,
                Math.abs((left + right) / 2f - width / 2f) <= dp(12f));
    }

    /**
     * The about heading is a single tracked line, so it is the one piece of copy that can outgrow
     * a narrow phone. Nothing in its band may touch the screen edge, or the string is being cut.
     */
    private static void assertHeadingClearOfEdges(Fixture f, int top, int bottom) {
        int width = f.view.getWidth();
        float outer = outerPad(width);
        int limit = Math.round(outer + dp(4f));
        int worst = 0;
        for (int y = top; y < bottom; y++) {
            for (int x = 0; x < limit; x++) {
                if (!close(f.pixel(x, y), f.page, GLYPH)) {
                    worst = Math.max(worst, limit - x);
                }
            }
            for (int x = width - limit; x < width; x++) {
                if (!close(f.pixel(x, y), f.page, GLYPH)) {
                    worst = Math.max(worst, x - (width - limit));
                }
            }
        }
        assertTrue("heading band reaches the screen edge (overflow " + worst + " px)", worst == 0);
    }

    /** Lowest row of the panel that still carries copy, so an overflow shows up as a big y. */
    private static int lastInkRow(Fixture f, int stripLeft) {
        for (int y = f.view.getHeight() - 1; y >= 0; y--) {
            int found = 0;
            for (int x = stripLeft; x < f.view.getWidth(); x += 2) {
                if (!close(f.pixel(x, y), f.page, GLYPH)) {
                    if (++found > 3) {
                        return y;
                    }
                }
            }
        }
        return -1;
    }

    private static void tap(ClockView view, float x, float y) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_UP, x, y, 0));
    }

    private static int countNear(Bitmap bitmap, int colour, int from, int top, int right, int bottom,
                                 int tolerance) {
        int matched = 0;
        for (int y = top; y < bottom; y += 4) {
            for (int x = from; x < right; x += 4) {
                if (close(bitmap.getPixel(x, y), colour, tolerance)) {
                    matched++;
                }
            }
        }
        return matched;
    }

    private static int countEqual(Bitmap bitmap, int colour, int from, int top, int right, int bottom) {
        int matched = 0;
        for (int y = top; y < bottom; y += 4) {
            for (int x = from; x < right; x += 4) {
                if (bitmap.getPixel(x, y) == colour) {
                    matched++;
                }
            }
        }
        return matched;
    }

    private static boolean close(int pixel, int colour, int tolerance) {
        return Math.abs(Color.red(pixel) - Color.red(colour)) <= tolerance
                && Math.abs(Color.green(pixel) - Color.green(colour)) <= tolerance
                && Math.abs(Color.blue(pixel) - Color.blue(colour)) <= tolerance;
    }

    private static float dp(float value) {
        return value * DENSITY;
    }

    private void save(Bitmap bitmap) throws Exception {
        File dir = new File("build/render-check");
        dir.mkdirs();
        File file = new File(dir, name.getMethodName() + ".png");
        FileOutputStream out = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }
}
