package com.genymobile.scrcpy.video;

public final class BitrateLadder {

    public static final class Level {
        private final int maxSize;
        private final int fps;
        private final int bitRate;

        Level(int maxSize, int fps, int bitRate) {
            this.maxSize = maxSize;
            this.fps = fps;
            this.bitRate = bitRate;
        }

        public int getMaxSize() {
            return maxSize;
        }

        public int getFps() {
            return fps;
        }

        public int getBitRate() {
            return bitRate;
        }
    }

    private static final Level[] LEVELS = {
            new Level(854, 24, 1_000_000),
            new Level(960, 30, 1_500_000),
            new Level(1280, 30, 2_500_000),
            new Level(1280, 60, 5_000_000),
            new Level(1920, 60, 10_000_000),
    };

    private static final double DOWNSCALE_FACTOR = 0.8;
    private static final double UPSCALE_FACTOR = 1.2;

    private int level;
    private long lastChangeMs = Long.MIN_VALUE;
    private long upgradeSinceMs = -1;

    public BitrateLadder() {
        // Start at the highest level; the first bandwidth feedback adapts it down.
        this(LEVELS.length - 1);
    }

    public BitrateLadder(int initialLevel) {
        this.level = initialLevel;
    }

    public Level current() {
        return LEVELS[level];
    }

    public boolean update(int bps) { return update(bps, System.nanoTime() / 1000000); }
    public boolean update(int bps, long nowMs) {
        int newLevel = level;
        // Downgrade immediately when bandwidth falls below the current level (0.8 hysteresis)
        while (newLevel > 0 && bps < LEVELS[newLevel].bitRate * DOWNSCALE_FACTOR) {
            newLevel--;
        }
        // Upgrade when bandwidth exceeds the current level by a 1.2x headroom
        while (newLevel < LEVELS.length - 1 && bps > LEVELS[newLevel].bitRate * UPSCALE_FACTOR) {
            newLevel++;
        }
        boolean upgrade = newLevel > level;
        if (upgrade) {
            // Upgrades must be sustained for 5 seconds to avoid flapping
            if (upgradeSinceMs < 0) { upgradeSinceMs = nowMs; }
            if (nowMs - upgradeSinceMs < 5000) { return false; }
        } else {
            upgradeSinceMs = -1;
        }
        if (newLevel != level) {
            // Enforce a minimum 1-second interval between level changes
            if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < 1000) { return false; }
            lastChangeMs = nowMs;
            upgradeSinceMs = -1;
            level = newLevel;
            return true;
        }
        return false;
    }
}
