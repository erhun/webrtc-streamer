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
            new Level(1920, 60, 10_000_000),
            new Level(1280, 60, 5_000_000),
            new Level(1280, 30, 2_500_000),
            new Level(960, 30, 1_500_000),
            new Level(854, 24, 1_000_000),
    };

    private static final double DOWNSCALE_FACTOR = 0.8;
    private static final double UPSCALE_FACTOR = 1.2;

    private int level;

    public BitrateLadder() {
        this(0);
    }

    public BitrateLadder(int initialLevel) {
        this.level = initialLevel;
    }

    public Level current() {
        return LEVELS[level];
    }

    public boolean update(int bps) {
        int newLevel = level;
        while (newLevel < LEVELS.length - 1 && bps < LEVELS[newLevel].bitRate * DOWNSCALE_FACTOR) {
            newLevel++;
        }
        while (newLevel > 0 && bps > LEVELS[newLevel - 1].bitRate * UPSCALE_FACTOR) {
            newLevel--;
        }
        if (newLevel != level) {
            level = newLevel;
            return true;
        }
        return false;
    }
}
