package dev.dbevil.remotesms;

final class EmailRetryPolicy {
    private static final long MINUTE_MS = 60_000L;
    private static final long MAX_DELAY_MS = 60 * MINUTE_MS;
    private static final int LOW_BATTERY_THRESHOLD = 20;
    private static final int LOW_BATTERY_RESET_LEVEL = 25;

    private EmailRetryPolicy() {
    }

    static long nextDelayMs(int attempts) {
        if (attempts <= 0) return 0L;
        if (attempts == 1) return MINUTE_MS;
        if (attempts == 2) return 5 * MINUTE_MS;
        if (attempts == 3) return 15 * MINUTE_MS;
        return MAX_DELAY_MS;
    }

    static boolean shouldSendLowBatteryAlert(int level, boolean charging, boolean alertActive) {
        return level >= 0 && level < LOW_BATTERY_THRESHOLD && !charging && !alertActive;
    }

    static boolean shouldResetLowBatteryAlert(int level, boolean charging) {
        return charging || level >= LOW_BATTERY_RESET_LEVEL;
    }
}
