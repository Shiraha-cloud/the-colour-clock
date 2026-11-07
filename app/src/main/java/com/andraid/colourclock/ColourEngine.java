package com.andraid.colourclock;

/**
 * The wall clock maps onto six hex digits, each 0..f: hour band / hour fraction,
 * minute band / minute fraction, second band / second fraction. Hours ignore seconds
 * and minutes ignore milliseconds. The rounding and flooring order is fixed, because
 * re-ordering it would shift digits and break the day-long colour sequence.
 */
public final class ColourEngine {

    private ColourEngine() {
    }

    public static String hex(int hours, int minutes, int seconds, int millis) {
        char[] out = new char[6];

        double hourFloat = hours + minutes / 60.0;
        out[0] = digit(Math.round(hourFloat / 24.0 * 15.0));
        double hourSpan = hours >= 20 ? hourFloat / 4.0 : hourFloat / 10.0;
        out[1] = digit(Math.round((hourSpan - Math.floor(hourSpan)) * 15.0));

        double minuteFloat = minutes + seconds / 60.0;
        out[2] = digit(Math.round(minuteFloat / 60.0 * 15.0));
        double minuteSpan = minuteFloat / 10.0;
        out[3] = digit(Math.round((minuteSpan - Math.floor(minuteSpan)) * 15.0));

        double secondFloat = seconds + millis / 1000.0;
        out[4] = digit(Math.round(secondFloat / 60.0 * 15.0));
        double secondSpan = secondFloat / 10.0;
        out[5] = digit(Math.round((secondSpan - Math.floor(secondSpan)) * 15.0));

        return new String(out);
    }

    private static char digit(long value) {
        return value < 10 ? (char) ('0' + value) : Character.forDigit((int) value, 16);
    }
}
