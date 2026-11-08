package com.andraid.colourclock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
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
import org.robolectric.Robolectric;
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

    /**
     * The size control is the second header disc. Its strip has to open and close on the same
     * tap, drag the readout between SCALE_MIN and SCALE_MAX, and survive the next start-up.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void sliderResizesTheReadoutAndStaysRemembered() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        float y = sliderY(PORTRAIT_W, PORTRAIT_H);
        f.draw();
        assertEquals(ClockView.SCALE_DEFAULT, f.view.getTextScale());
        int closed = stripInk(f, y);
        assertTrue("ink already sits on the strip: " + closed, closed < 20);

        tap(f.view, sizeCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertTrue("slider did not appear (" + stripInk(f, y) + " ink px)", stripInk(f, y) > 500);

        drag(f.view, sliderLeft(PORTRAIT_W), y, sliderRight(PORTRAIT_W));
        f.settle();
        f.draw();
        assertEquals(ClockView.SCALE_MAX, f.view.getTextScale());
        save(f.bitmap, "-open-max");
        // Measured across the band, stopping above the strip so the track is never mistaken
        // for a glyph: the baseline stays put, so it is the span that grows with the size.
        int from = Math.round(bandTop(PORTRAIT_W));
        int to = Math.round(y - dp(30f));
        int large = readoutSpan(f, from, to);

        drag(f.view, sliderRight(PORTRAIT_W), y, sliderLeft(PORTRAIT_W));
        f.settle();
        f.draw();
        assertEquals(ClockView.SCALE_MIN, f.view.getTextScale());
        int small = readoutSpan(f, from, to);
        assertTrue("dragging did not change the glyphs: " + small + " vs " + large,
                large > small * 3 / 2);

        // The same preference feeds the next view, so the size survives the process.
        assertEquals("value written through", ClockView.SCALE_MIN, RuntimeEnvironment
                .getApplication().getSharedPreferences(ClockView.PREFS, Context.MODE_PRIVATE)
                .getInt(ClockView.PREF_SCALE, ClockView.SCALE_DEFAULT));
        ClockView again = new ClockView(RuntimeEnvironment.getApplication());
        assertEquals(ClockView.SCALE_MIN, again.getTextScale());

        tap(f.view, sizeCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertTrue("slider did not go away (" + stripInk(f, y) + " ink px)", stripInk(f, y) < 20);
        save(f.bitmap);
    }

    /**
     * Asking for the largest size must still leave the string whole: the fit clamps are the last
     * word, and the readout may not reach down into the strip it is being adjusted with.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void largestScaleStaysInsideTheContent() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        f.view.setTextScale(ClockView.SCALE_MAX);
        f.settle();
        f.draw();
        assertReadoutNotClipped(f);

        float y = sliderY(PORTRAIT_W, PORTRAIT_H);
        int bottom = readoutBottom(f, Math.round(y));
        assertTrue("readout reaches the strip: " + bottom + " of " + y, bottom < y - dp(20f));
        save(f.bitmap);
    }

    /**
     * Landscape has vertical room to spare, so the largest scale is really reached there instead
     * of being cut off by the screen width. The readout still has to stop short of the strip, and
     * the strip still has to be readable over the widest the digits ever get.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-notnight-420dpi")
    public void landscapeLargestScaleClearsTheSlider() throws Exception {
        Fixture f = new Fixture(LANDSCAPE_W, LANDSCAPE_H, 0xFFFFFFFF, 0);
        f.view.setTextScale(ClockView.SCALE_MAX);
        f.settle();
        f.draw();
        assertReadoutNotClipped(f);

        float y = sliderY(LANDSCAPE_W, LANDSCAPE_H);
        int bottom = readoutBottom(f, Math.round(y));
        assertTrue("readout reaches the strip: " + bottom + " of " + y, bottom < y - dp(20f));
        assertTrue("the scale did not actually grow the readout: " + bottom, bottom > y - dp(150f));

        // Open the strip over that oversized readout, which is the tightest the layout ever gets.
        tap(f.view, sizeCentreX(LANDSCAPE_W, true), infoCentreY(LANDSCAPE_W, 0));
        f.settle();
        f.draw();
        assertTrue("no slider in the wide layout (" + stripInk(f, y) + " ink px)",
                stripInk(f, y) > 500);
        save(f.bitmap);
    }

    /** Both panels own the whole screen, so the second one has to put the first away. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void sliderGivesWayToTheAboutPanel() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        tap(f.view, sizeCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        float y = sliderY(PORTRAIT_W, PORTRAIT_H);
        assertTrue("slider missing after its toggle (" + stripInk(f, y) + " ink px)",
                stripInk(f, y) > 500);

        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        int below = Math.round(dp(320f));
        int ink = countNear(f.bitmap, f.ink, 0, below, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("about copy did not open (" + ink + " ink px)", ink > 200);

        // Close the panel again: the strip only stays empty if it was really put away, because an
        // open slider would simply wait there for the about panel to leave.
        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertTrue("slider survived the about panel: " + stripInk(f, y), stripInk(f, y) < 20);
        save(f.bitmap);
    }

    /**
     * The third disc opens a card of its own, floating over the clock instead of pushing it
     * away like the about panel does. It is opaque, so its box stops showing the clock colour
     * the moment it is up, and nothing else on the screen may move.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void menuDiscOpensTheCard() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        assertEquals(ClockView.STYLE_CLOCK, f.view.getStyle());
        assertEquals(ClockView.PERIOD_DEFAULT, f.view.getPeriodMinutes());
        assertFalse("menu open at start-up", f.view.isMenuOpen());

        f.draw();
        int samples = cardSamples(f);
        assertTrue("the card box does not show the clock colour yet",
                menuCardPixels(f) < samples / 20);

        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        int covered = menuCardPixels(f);
        assertTrue("menu card did not open (" + covered + "/" + samples + ")",
                covered > samples * 3 / 4);
        assertTrue(f.view.isMenuOpen());
        assertFalse("the menu opened the slider too", f.view.isSizeOpen());
        assertEquals("opening the menu changed the mode", ClockView.STYLE_CLOCK, f.view.getStyle());
        assertTrue("pill row vanished behind the menu", pillInk(f) > 300);
        save(f.bitmap);
    }

    /**
     * The card is measured from the header rather than the screen, so the wide layout has to be
     * checked on its own: 411dp of height is not much room for a heading, two rows and a track.
     */
    @Test
    @Config(qualifiers = "w914dp-h411dp-land-notnight-420dpi")
    public void landscapeMenuKeepsTheCardInside() throws Exception {
        Fixture f = new Fixture(LANDSCAPE_W, LANDSCAPE_H, 0xFFFFFFFF, 0);
        tap(f.view, menuCentreX(LANDSCAPE_W, true), infoCentreY(LANDSCAPE_W, 0));
        f.settle();
        f.draw();
        assertTrue("landscape card did not open",
                menuCardPixels(f) > cardSamples(f) * 3 / 4);
        assertTrue("the track ran off the screen", periodTrackY(LANDSCAPE_W, 0) < LANDSCAPE_H - dp(8f));

        float rowX = (cardLeft(LANDSCAPE_W) + cardRight(LANDSCAPE_W)) / 2f;
        tap(f.view, rowX, loopRowY(LANDSCAPE_W, 0));
        f.settle();
        f.draw();
        assertEquals(ClockView.STYLE_LOOP, f.view.getStyle());
        assertFalse("picking a mode left the card open", f.view.isMenuOpen());
        assertColourReachesEveryEdge(f);
        save(f.bitmap);
    }

    /** A tap that misses the card only puts it away; it must not reach the clock underneath. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void tapBehindTheMenuClosesIt() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertTrue("card did not open", menuCardPixels(f) > cardSamples(f) * 3 / 4);

        // Left of the card and below it, which is exactly where the big readout lives.
        tap(f.view, dp(40f), Math.round(periodTrackY(PORTRAIT_W, 0) + dp(120f)));
        f.settle();
        f.draw();
        assertFalse("tap outside the card left it open", f.view.isMenuOpen());
        assertTrue("card box still covered", menuCardPixels(f) < cardSamples(f) / 20);
        assertEquals(ClockView.STYLE_CLOCK, f.view.getStyle());
        assertEquals("tap reached through the card", ClockView.MODE_TIME, f.view.getMode());
        save(f.bitmap);
    }

    /**
     * Jack Hughes is the other half of the same menu, so picking it closes the card, and it
     * takes the two capsules with it: their hex readout means nothing once the colour has
     * stopped being the time.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void loopModeTakesThePillsAway() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        assertTrue("the hex mode still has its capsules", pillInk(f) > 300);

        tap(f.view, cardRight(PORTRAIT_W) - dp(40f), loopRowY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertEquals(ClockView.STYLE_LOOP, f.view.getStyle());
        assertEquals("a ring has no hex to show", ClockView.MODE_TIME, f.view.getMode());
        assertFalse("menu did not close", f.view.isMenuOpen());
        assertTrue("capsules survived the ring: " + pillInk(f), pillInk(f) < 60);

        // What is on screen is the ring at this instant, which f.colour now reports.
        int samples = (PORTRAIT_W / 4) * (PORTRAIT_H / 4);
        int matched = countNear(f.bitmap, f.colour, 0, 0, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("screen is not the ring colour (" + matched + "/" + samples + ")",
                matched > samples * 8 / 10);
        assertReadoutNotClipped(f);
        save(f.bitmap, "-loop");

        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        tap(f.view, cardRight(PORTRAIT_W) - dp(40f), clockRowY(PORTRAIT_W, 0));
        f.settle();
        f.draw();
        assertEquals(ClockView.STYLE_CLOCK, f.view.getStyle());
        assertTrue("capsules did not come back", pillInk(f) > 300);
        save(f.bitmap, "-back");
    }

    /**
     * The period track is the only place the length of a lap is set. It snaps to the eighteen
     * stops, picks the ring mode for you if you grab it anyway, survives the next start-up, and
     * the screen then paints the ring for the lap that was chosen.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void periodTrackSnapsAndRemembers() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();

        float y = periodTrackY(PORTRAIT_W, 0);
        drag(f.view, periodLeft(PORTRAIT_W), y, periodRight(PORTRAIT_W));
        f.settle();
        assertEquals(ClockView.STYLE_LOOP, f.view.getStyle());
        assertEquals("far right is a day", 1440, f.view.getPeriodMinutes());

        // A quarter of the way along is the fifth stop, not a quarter of a day.
        drag(f.view, periodRight(PORTRAIT_W), y,
                periodLeft(PORTRAIT_W) + (periodRight(PORTRAIT_W) - periodLeft(PORTRAIT_W)) / 4f);
        f.settle();
        assertEquals("the stops are evenly spaced", 10, f.view.getPeriodMinutes());

        assertEquals("value written through", 10, RuntimeEnvironment.getApplication()
                .getSharedPreferences(ClockView.PREFS, Context.MODE_PRIVATE)
                .getInt(ClockView.PREF_PERIOD, 0));
        assertEquals(10, new ClockView(RuntimeEnvironment.getApplication()).getPeriodMinutes());
        assertEquals(10, f.view.getPeriodMinutes());

        // The shortest lap is a minute; the pixels have to sit on that ring, not the day-long one.
        drag(f.view, periodRight(PORTRAIT_W), y, periodLeft(PORTRAIT_W));
        f.settle();
        f.draw();
        assertEquals(1, f.view.getPeriodMinutes());
        // A corner clear of the digits, the card and the capsules, so this pixel is the colour.
        int probeX = Math.round(dp(20f));
        int probeY = Math.round(dp(700f));
        assertTrue("minute lap painted off its own ring",
                close(f.pixel(probeX, probeY), f.colour, SHADE));
        save(f.bitmap, "-ring");
    }

    /** Three panels now share the header, so opening one still puts every other one away. */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void aboutPanelClosesTheMenu() throws Exception {
        Fixture f = new Fixture(PORTRAIT_W, PORTRAIT_H, 0xFFFFFFFF, 0);
        tap(f.view, menuCentreX(PORTRAIT_W, false), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        tap(f.view, infoCentreX(PORTRAIT_W), infoCentreY(PORTRAIT_W, 0));
        f.settle();
        f.draw();

        assertTrue(f.view.isAboutOpen());
        assertFalse("menu survived the about panel", f.view.isMenuOpen());
        int below = Math.round(dp(320f));
        int copy = countNear(f.bitmap, f.ink, 0, below, PORTRAIT_W, PORTRAIT_H, SHADE);
        assertTrue("about copy did not open (" + copy + " ink px)", copy > 200);
        save(f.bitmap);
    }

    /**
     * The handset rotated the wrong way in reverse portrait because the manifest asked for
     * "user", which leaves that quarter turn out. FULL_USER is only set from API 18 up, so
     * the old releases have to keep the manifest value rather than an unknown constant.
     */
    @Test
    @Config(qualifiers = "w411dp-h914dp-port-notnight-420dpi")
    public void reversePortraitIsAllowedFromApi18() throws Exception {
        Activity activity = Robolectric.buildActivity(MainActivity.class).setup().get();
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.getRequestedOrientation());
        activity.finish();
    }

    // ---------------------------------------------------------------- fixtures

    private final class Fixture {
        final ClockView view;
        final Bitmap bitmap;
        final Canvas canvas;
        final int page;
        /** What the screen is painted right now, which depends on which colour source is live. */
        int colour;
        int ink;

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
            this.page = page;
            settle();
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
            // Long enough for the 400ms about panel, 260ms slider and 450ms swap to finish.
            for (int i = 0; i < 40; i++) {
                shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.MILLISECONDS);
            }
            resync();
        }

        /**
         * The last tick ran at the end of the idle window, so the colour the view settled on is
         * the one this instant maps to, whichever of the two sources the mode is using.
         */
        void resync() {
            Calendar now = Calendar.getInstance();
            now.setTimeInMillis(System.currentTimeMillis());
            String hex = view.getStyle() == ClockView.STYLE_LOOP
                    ? ColourLoop.hexAt(now.getTimeInMillis(), view.getPeriodMinutes())
                    : ColourEngine.hex(now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE),
                            now.get(Calendar.SECOND), now.get(Calendar.MILLISECOND));
            colour = 0xFF000000 | Integer.parseInt(hex, 16);
            ink = ClockView.readable(colour, page);
        }
    }

    // ---------------------------------------------------------------- geometry

    /** Content is padded this far inside the view; mirrors the clamp(1vw, 10px..22px) rule. */
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

    /** Mirrors layout(): the wide layout gives the header more padding. */
    private static float headerPad(int width) {
        return width > dp(800) ? dp(28f) : dp(20f);
    }

    private static float sizeCentreX(int width, boolean wide) {
        return infoCentreX(width, wide) - dp(12f) - dp(40f);
    }

    private static float menuCentreX(int width, boolean wide) {
        return sizeCentreX(width, wide) - dp(12f) - dp(40f);
    }

    // The card hangs from the menu disc; these mirror layoutMenu() line for line.
    private static float cardRight(int width) {
        return width - outerPad(width) - headerPad(width);
    }

    private static float cardLeft(int width) {
        float contentW = width - outerPad(width) * 2f;
        return cardRight(width) - Math.min(dp(300f), contentW - headerPad(width) * 2f);
    }

    private static float cardTop(int width, int safeTop) {
        return outerPad(width) + safeTop + headerPad(width) + dp(40f) + dp(12f);
    }

    private static float menuHeadingY(int width, int safeTop) {
        return cardTop(width, safeTop) + dp(18f) + dp(10f);
    }

    private static float clockRowY(int width, int safeTop) {
        return menuHeadingY(width, safeTop) + dp(11f) + dp(44f) / 2f;
    }

    private static float loopRowY(int width, int safeTop) {
        return clockRowY(width, safeTop) + dp(44f);
    }

    private static float periodTrackY(int width, int safeTop) {
        return menuHeadingY(width, safeTop) + dp(11f) + dp(88f) + dp(8f) + dp(12f) + dp(26f);
    }

    private static float periodLeft(int width) {
        return cardLeft(width) + dp(18f);
    }

    private static float periodRight(int width) {
        return cardRight(width) - dp(18f);
    }

    /** The strip floats above the pill row: band bottom, then the pill gap, then 18dp. */
    private static float sliderY(int width, int height) {
        float bandBottom = pillMidY(width, height, 0) - dp(48f) / 2f;
        return bandBottom - Math.max(height * 0.0575f, dp(14f)) - dp(18f);
    }

    private static float sliderLeft(int width) {
        return outerPad(width) + headerPad(width) + dp(44f);
    }

    /** Mirrors layoutReadout(): the band starts under the header row and ends at the pill row. */
    private static float bandTop(int width) {
        return outerPad(width) + headerPad(width) * 2f + dp(40f);
    }

    private static float sliderRight(int width) {
        return width - outerPad(width) - headerPad(width) - dp(44f);
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

    private static void drag(ClockView view, float fromX, float y, float toX) {
        long now = SystemClock.uptimeMillis();
        view.dispatchTouchEvent(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, fromX, y, 0));
        for (int i = 1; i <= 4; i++) {
            float x = fromX + (toX - fromX) * i / 4f;
            view.dispatchTouchEvent(MotionEvent.obtain(now, now + i, MotionEvent.ACTION_MOVE, x, y, 0));
        }
        view.dispatchTouchEvent(MotionEvent.obtain(now, now + 5, MotionEvent.ACTION_UP, toX, y, 0));
    }

    /**
     * The strip carries nothing until the size panel opens, so ink there is the panel itself.
     * The track is drawn at a third of full alpha on purpose, which is inside the GLYPH slack
     * used for solid copy, so this asks for a near-exact match against the card colour instead.
     */
    private static int stripInk(Fixture f, float y) {
        int width = f.view.getWidth();
        int from = Math.round(sliderLeft(width)) - Math.round(dp(20f));
        int right = Math.round(sliderRight(width)) + Math.round(dp(20f));
        int top = Math.round(y - dp(14f));
        int bottom = Math.round(y + dp(14f));
        int found = 0;
        for (int row = top; row < bottom; row++) {
            for (int x = from; x < right; x++) {
                if (!close(f.pixel(x, row), f.colour, 16)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** The card box, shrunk a little so the rounded corners never count as copy. */
    private static int[] cardBox(Fixture f) {
        int width = f.view.getWidth();
        return new int[]{Math.round(cardLeft(width)) + 8, Math.round(cardTop(width, 0)) + 8,
                Math.round(cardRight(width)) - 8, Math.round(periodTrackY(width, 0))};
    }

    /** Samples the card box holds, so the counts below can be read as fractions. */
    private static int cardSamples(Fixture f) {
        int[] box = cardBox(f);
        return ((box[3] - box[1]) / 4) * ((box[2] - box[0]) / 4);
    }

    /** The card is opaque, so anything in its box that is not the clock colour is the card. */
    private static int menuCardPixels(Fixture f) {
        int[] box = cardBox(f);
        int found = 0;
        for (int y = box[1]; y < box[3]; y += 4) {
            for (int x = box[0]; x < box[2]; x += 4) {
                if (!close(f.pixel(x, y), f.colour, 16)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** Anything in the capsule row that is not the card colour is a pill being drawn. */
    private static int pillInk(Fixture f) {
        int width = f.view.getWidth();
        int height = f.view.getHeight();
        int mid = Math.round(pillMidY(width, height, 0));
        int from = Math.round(outerPad(width)) + 4;
        int right = Math.round(width - outerPad(width)) - 4;
        int found = 0;
        for (int y = mid - Math.round(dp(24f)); y < mid + Math.round(dp(24f)); y++) {
            for (int x = from; x < right; x++) {
                if (!close(f.pixel(x, y), f.colour, 16)) {
                    found++;
                }
            }
        }
        return found;
    }

    /** Widest left-to-right span of ink between two rows, which is what the size scale moves. */
    private static int readoutSpan(Fixture f, int fromRow, int toRow) {
        int width = f.view.getWidth();
        float outer = outerPad(width);
        int from = Math.round(outer) + 4;
        int right = Math.round(width - outer) - 4;
        int left = right, found = -1;
        for (int y = fromRow; y < toRow; y += 2) {
            for (int x = from; x < right; x++) {
                if (!close(f.pixel(x, y), f.colour, GLYPH)) {
                    left = Math.min(left, x);
                    found = Math.max(found, x);
                }
            }
        }
        return found - left;
    }

    /** Lowest row the readout reaches, searched above the strip so the two are never confused. */
    private static int readoutBottom(Fixture f, int limit) {
        int width = f.view.getWidth();
        float outer = outerPad(width);
        int from = Math.round(outer) + 4;
        int right = Math.round(width - outer) - 4;
        int best = -1;
        for (int y = 0; y < limit; y++) {
            int found = 0;
            for (int x = from; x < right; x += 2) {
                if (!close(f.pixel(x, y), f.colour, GLYPH)) {
                    found++;
                }
            }
            if (found > 8) {
                best = y;
            }
        }
        return best;
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
        save(bitmap, "");
    }

    private void save(Bitmap bitmap, String suffix) throws Exception {
        File dir = new File("build/render-check");
        dir.mkdirs();
        File file = new File(dir, name.getMethodName() + suffix + ".png");
        FileOutputStream out = new FileOutputStream(file);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        out.close();
    }
}
