package com.genymobile.scrcpy.video;

import java.util.Objects;

public final class BitrateLadder {

    public static final class Level {
        private final int maxSize;
        private final int fps;
        private final int bitRate;

        public Level(int maxSize, int fps, int bitRate) {
            this.maxSize = maxSize;
            this.fps = fps;
            this.bitRate = bitRate;
        }

        public int getMaxSize() { return maxSize; }
        public int getFps() { return fps; }
        public int getBitRate() { return bitRate; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Level level = (Level) o;
            return maxSize == level.maxSize && fps == level.fps && bitRate == level.bitRate;
        }

        @Override
        public int hashCode() {
            return Objects.hash(maxSize, fps, bitRate);
        }

        @Override
        public String toString() {
            return "Level{" + maxSize + "p, " + fps + "fps, " + (bitRate / 1000) + "kbps}";
        }
    }

    // 默认预设：兼顾云手机/云游戏画质与流畅度
    public static final Level[] DEFAULT_LEVELS = {
            new Level(854, 24, 1_200_000),   // 480p 24fps 1.2M (极弱网保底)
            new Level(960, 30, 2_000_000),   // 540p 30fps 2.0M
            new Level(1280, 30, 4_000_000),  // 720p 30fps 4.0M (高清基础，保证高频细节)
            new Level(1280, 60, 6_000_000),  // 720p 60fps 6.0M (高帧率流畅)
            new Level(1600, 60, 8_000_000), // 1080p 60fps 12.0M (超清)
            new Level(1920, 60, 12_000_000), // 1080p 60fps 12.0M (超清)
    };

    private static final double DOWNSCALE_FACTOR = 0.8;
    private static final double UPSCALE_FACTOR = 1.1;

    private final Level[] levels;
    private volatile int level; // 确保 update() 线程与 current() 编码线程间的可见性
    private long lastChangeMs = Long.MIN_VALUE;
    private long upgradeSinceMs = -1;

    public BitrateLadder() {
        this(DEFAULT_LEVELS, DEFAULT_LEVELS.length - 1);
    }

    public BitrateLadder(Level[] levels) {
        this(levels, levels.length - 1);
    }

    public BitrateLadder(Level[] levels, int initialLevel) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("Levels array must not be null or empty");
        }
        this.levels = levels.clone();
        this.level = Math.max(0, Math.min(initialLevel, this.levels.length - 1));
    }

    public Level current() {
        return levels[level];
    }

    public int getCurrentLevelIndex() {
        return level;
    }

    public boolean update(int bps) {
        return update(bps, System.nanoTime() / 1_000_000);
    }

    public boolean update(int bps, long nowMs) {
        int oldLevel = level;
        int newLevel = oldLevel;

        // 1. 降级计算：估算带宽低于当前 Level 的 80% 时触发降级
        while (newLevel > 0 && bps < levels[newLevel].bitRate * DOWNSCALE_FACTOR) {
            newLevel--;
        }

        // 2. 升级计算：估算带宽能够稳定支撑下一 Level 码率的 1.1 倍余量
        while (newLevel < levels.length - 1 && bps > levels[newLevel + 1].bitRate * UPSCALE_FACTOR) {
            newLevel++;
        }

        boolean isUpgrade = newLevel > oldLevel;
        boolean isSevereDowngrade = (oldLevel - newLevel) >= 2; // 断崖式弱网（跨 2 级及以上降级）

        if (isUpgrade) {
            // 升阶防抖：网络改善必须稳定维持 3 秒
            if (upgradeSinceMs < 0) {
                upgradeSinceMs = nowMs;
            }
            if (nowMs - upgradeSinceMs < 3000) {
                return false;
            }
        } else {
            upgradeSinceMs = -1;
        }

        if (newLevel != oldLevel) {
            // 剧烈弱网强行绕过 1 秒冷却以快速降低带宽，常规微调保留 1 秒防抖
            if (!isSevereDowngrade && lastChangeMs != Long.MIN_VALUE && (nowMs - lastChangeMs < 1000)) {
                return false;
            }

            lastChangeMs = nowMs;
            upgradeSinceMs = -1;
            level = newLevel;
            return true;
        }

        return false;
    }

    /**
     * 判断两次 Level 变更是否涉及到分辨率或帧率的变化
     * 用于决策：仅调 Bitrate（使用 MediaCodec.setParameters）还是重置视频捕获/编码流
     */
    public static boolean isResolutionOrFpsChanged(Level oldLevel, Level newLevel) {
        if (oldLevel == null || newLevel == null) return true;
        return oldLevel.getMaxSize() != newLevel.getMaxSize() || oldLevel.getFps() != newLevel.getFps();
    }
}