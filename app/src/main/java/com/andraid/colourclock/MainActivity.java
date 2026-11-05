package com.andraid.colourclock;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

/**
 * Hosts one ClockView. Rotation is handled by the view, so turning the phone never
 * restarts the activity and the colour never flashes.
 */
public class MainActivity extends Activity {

    private ClockView clock;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        clock = new ClockView(this);
        clock.restoreMode(getSharedPreferences(ClockView.PREFS, MODE_PRIVATE)
                .getInt(ClockView.PREF_MODE, ClockView.MODE_TIME));
        setContentView(clock);
        allowDisplayCutout();
        installInsets();
        applyImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Picks up a time zone or night mode that changed while we were paused.
        clock.onEnvironmentChanged(getResources().getConfiguration());
        applyImmersive();
        clock.startTicking();
    }

    @Override
    protected void onPause() {
        clock.stopTicking();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        clock.onEnvironmentChanged(newConfig);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    private void allowDisplayCutout() {
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams attrs = getWindow().getAttributes();
            attrs.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(attrs);
        }
    }

    /** Fullscreen on every release from 4.0 up: two flag generations plus the API 30 controller. */
    private void applyImmersive() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
            return;
        }
        int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        if (Build.VERSION.SDK_INT >= 19) {
            flags |= View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        }
        clock.setSystemUiVisibility(flags);
    }

    private void installInsets() {
        if (Build.VERSION.SDK_INT >= 21) {
            clock.setOnApplyWindowInsetsListener(new InsetsReader());
            clock.requestApplyInsets();
        }
    }

    /** Only ever instantiated on API 21+, so the newer types stay out of the old verifier. */
    @TargetApi(21)
    private static class InsetsReader implements View.OnApplyWindowInsetsListener {
        @Override
        public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
            // The four getters report zero for a bar we are laid out behind, which is what the
            // immersive flags ask for; WindowInsets.Type would report it regardless.
            int left = insets.getSystemWindowInsetLeft();
            int top = insets.getSystemWindowInsetTop();
            int right = insets.getSystemWindowInsetRight();
            int bottom = insets.getSystemWindowInsetBottom();
            if (Build.VERSION.SDK_INT >= 28) {
                int[] cutout = Cutout.read(insets);
                left = Math.max(left, cutout[0]);
                top = Math.max(top, cutout[1]);
                right = Math.max(right, cutout[2]);
                bottom = Math.max(bottom, cutout[3]);
            }
            ((ClockView) view).setSafeInsets(left, top, right, bottom);
            return insets;
        }
    }

    /** Split out so DisplayCutout, which arrived in API 28, is only loaded where it exists. */
    @TargetApi(28)
    private static final class Cutout {
        static int[] read(WindowInsets insets) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout == null) {
                return new int[4];
            }
            return new int[]{cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(),
                    cutout.getSafeInsetRight(), cutout.getSafeInsetBottom()};
        }
    }
}
