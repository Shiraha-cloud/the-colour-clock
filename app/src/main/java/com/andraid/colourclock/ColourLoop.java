package com.andraid.colourclock;

/**
 * The colour that drifts on its own. One closed ring is walked at constant perceptual speed:
 * every point keeps the same OKLab lightness and takes the most colourful in-gamut chroma the
 * angle allows, then the chroma curve is smoothed so no part of the ring shows a seam.
 * The table only depends on the lightness and the sample count, so it is built once per process.
 */
public final class ColourLoop {

    static final double LIGHTNESS = 0.72;
    /** Angles sampled around the ring; also the resolution of the arc-length table. */
    static final int SAMPLES = 4096;
    private static final double STEP = 2 * Math.PI / SAMPLES;

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private static double[] xs;
    private static double[] ys;
    private static double[] distances;
    private static double length;

    private ColourLoop() {
    }

    /** Wall-clock aligned, so a restart lands on the colour the ring is already at. */
    public static String hexAt(long epochMillis, int periodMinutes) {
        long period = periodMinutes * 60000L;
        return hexAt(epochMillis % period / (double) period);
    }

    /** Phase runs 0..1 once around the ring. */
    public static String hexAt(double phase) {
        int[] rgb = rgbAt(phase);
        char[] out = new char[6];
        for (int i = 0; i < 3; i++) {
            out[i * 2] = DIGITS[rgb[i] >> 4];
            out[i * 2 + 1] = DIGITS[rgb[i] & 15];
        }
        return new String(out);
    }

    /** The ring as red, green and blue bytes, at a phase that is allowed to wrap. */
    public static int[] rgbAt(double phase) {
        build();
        double wrapped = ((phase % 1d) + 1d) % 1d;
        double target = wrapped * length;
        int index = segmentFor(target);
        double span = distances[index + 1] - distances[index];
        double amount = span > 0d ? (target - distances[index]) / span : 0d;
        double a = xs[index] + (xs[index + 1] - xs[index]) * amount;
        double b = ys[index] + (ys[index + 1] - ys[index]) * amount;
        double[] linear = toLinear(a, b);
        return new int[]{toByte(linear[0]), toByte(linear[1]), toByte(linear[2])};
    }

    private static int segmentFor(double target) {
        int low = 0;
        int high = distances.length;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (distances[mid] <= target) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        int last = distances.length - 2;
        return low - 1 > last ? last : Math.max(low - 1, 0);
    }

    private static synchronized void build() {
        if (xs != null) {
            return;
        }
        double[] limits = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double cosine = Math.cos(STEP * i);
            double sine = Math.sin(STEP * i);
            double low = 0d;
            double high = 1d;
            for (int attempt = 0; attempt < 28; attempt++) {
                double chroma = (low + high) / 2d;
                if (inGamut(chroma * cosine, chroma * sine)) {
                    low = chroma;
                } else {
                    high = chroma;
                }
            }
            limits[i] = low;
        }

        double[] smoothed = smooth(limits);
        // Smoothing can lift a point outside the gamut, so one global factor pulls the whole
        // ring back in; clipping each point instead would flatten the speed in those places.
        double scale = 1d;
        for (int i = 0; i < SAMPLES; i++) {
            if (smoothed[i] > 0d) {
                double fit = 0.9d * limits[i] / smoothed[i];
                if (i == 0 || fit < scale) {
                    scale = fit;
                }
            }
        }

        xs = new double[SAMPLES + 1];
        ys = new double[SAMPLES + 1];
        for (int i = 0; i < SAMPLES; i++) {
            double chroma = scale * smoothed[i];
            xs[i] = chroma * Math.cos(STEP * i);
            ys[i] = chroma * Math.sin(STEP * i);
        }
        xs[SAMPLES] = xs[0];
        ys[SAMPLES] = ys[0];

        distances = new double[SAMPLES + 1];
        for (int i = 0; i < SAMPLES; i++) {
            // Every point shares the lightness, so the chord is also the three-dimensional distance.
            double da = xs[i + 1] - xs[i];
            double db = ys[i + 1] - ys[i];
            distances[i + 1] = distances[i] + Math.sqrt(da * da + db * db);
        }
        length = distances[SAMPLES];
    }

    /** Circular Gaussian, because the ring wraps and a straight average would break at the join. */
    private static double[] smooth(double[] limits) {
        double sigma = SAMPLES / 100d;
        int radius = (int) Math.ceil(sigma * 3d);
        int taps = radius * 2 + 1;
        double[] kernel = new double[taps];
        double weightSum = 0d;
        for (int k = 0; k < taps; k++) {
            double offset = k - radius;
            kernel[k] = Math.exp(-0.5 * (offset / sigma) * (offset / sigma));
            weightSum += kernel[k];
        }
        double[] smoothed = new double[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            double total = 0d;
            for (int k = 0; k < taps; k++) {
                int at = i + k - radius;
                if (at < 0) {
                    at += SAMPLES;
                } else if (at >= SAMPLES) {
                    at -= SAMPLES;
                }
                total += limits[at] * kernel[k];
            }
            smoothed[i] = total / weightSum;
        }
        return smoothed;
    }

    private static boolean inGamut(double a, double b) {
        double[] rgb = toLinear(a, b);
        for (int i = 0; i < 3; i++) {
            if (rgb[i] < 0d || rgb[i] > 1d) {
                return false;
            }
        }
        return true;
    }

    private static double[] toLinear(double a, double b) {
        double l = LIGHTNESS + 0.3963377774 * a + 0.2158037573 * b;
        double m = LIGHTNESS - 0.1055613458 * a - 0.0638541728 * b;
        double s = LIGHTNESS - 0.0894841775 * a - 1.2914855480 * b;
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        return new double[]{4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
                -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
                -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s};
    }

    private static int toByte(double linear) {
        double channel = linear < 0d ? 0d : (linear > 1d ? 1d : linear);
        double encoded = channel <= 0.0031308d ? 12.92d * channel
                : 1.055d * Math.pow(channel, 1d / 2.4d) - 0.055d;
        return (int) Math.floor(encoded * 255d + 0.5d);
    }
}
