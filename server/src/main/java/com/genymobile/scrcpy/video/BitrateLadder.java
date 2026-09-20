package com.genymobile.scrcpy.video;

import java.util.Objects;

public final class BitrateLadder {

    // ==================== Level ====================
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

    // ==================== 默认档位 ====================
    public static final Level[] DEFAULT_LEVELS = {
            new Level(854, 24, 1_200_000),
            new Level(960, 30, 2_000_000),
            new Level(1280, 30, 4_000_000),
            new Level(1280, 60, 6_000_000),
            new Level(1600, 60, 8_000_000),
            new Level(1920, 60, 12_000_000),
    };

    // ==================== 可配置参数 ====================
    public static final class Config {
        /** 带宽低于当前档码率 * downscaleFactor 时触发降档 */
        public double downscaleFactor = 0.8;
        /** 带宽高于下一档码率 * upscaleFactor 时触发升档 */
        public double upscaleFactor = 1.1;
        /** 升档防抖：需稳定维持的毫秒数 */
        public long upgradeStableMs = 5000L;
        /** 常规降档防抖：需持续低于阈值的毫秒数 */
        public long downgradeStableMs = 3000L;
        /** 严重降档（跨 ≥2 级）的最小冷却间隔 */
        public long severeDowngradeCooldownMs = 1000L;
        /** 快速升档通道：严重降档后，若网络迅速恢复，可缩短升档防抖 */
        public long fastUpgradeStableMs = 2000L;
        /** 触发快速升档通道的条件：距上次严重降档不超过该毫秒数 */
        public long fastUpgradeWindowMs = 10_000L;

        public Config() {}

        public Config copy() {
            Config c = new Config();
            c.downscaleFactor = downscaleFactor;
            c.upscaleFactor = upscaleFactor;
            c.upgradeStableMs = upgradeStableMs;
            c.downgradeStableMs = downgradeStableMs;
            c.severeDowngradeCooldownMs = severeDowngradeCooldownMs;
            c.fastUpgradeStableMs = fastUpgradeStableMs;
            c.fastUpgradeWindowMs = fastUpgradeWindowMs;
            return c;
        }
    }

    // ==================== 回调 ====================
    public interface OnLevelChangeListener {
        void onLevelChanged(Level oldLevel, Level newLevel, ChangeReason reason, long nowMs);
    }

    public enum ChangeReason {
        UPGRADE,
        DOWNGRADE,
        SEVERE_DOWNGRADE
    }

    // ==================== 字段 ====================
    private final Level[] levels;
    private final Config config;

    private volatile int level;
    private long lastChangeMs = Long.MIN_VALUE;
    private long lastSevereDowngradeMs = Long.MIN_VALUE;

    private long upgradeSinceMs = -1;
    private long downgradeSinceMs = -1;
    private int targetDowngradeLevel = -1;

    private OnLevelChangeListener listener;

    // ==================== 构造 ====================
    public BitrateLadder() {
        this(DEFAULT_LEVELS, DEFAULT_LEVELS.length / 2, new Config());
    }

    public BitrateLadder(Level[] levels) {
        this(levels, levels.length / 2, new Config());
    }

    public BitrateLadder(Level[] levels, int initialLevel) {
        this(levels, initialLevel, new Config());
    }

    public BitrateLadder(Level[] levels, int initialLevel, Config config) {
        if (levels == null || levels.length == 0) {
            throw new IllegalArgumentException("Levels array must not be null or empty");
        }
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null");
        }
        this.levels = levels.clone();
        this.config = config.copy();
        this.level = clamp(initialLevel, 0, this.levels.length - 1);
    }

    // ==================== 查询 ====================
    public Level current() {
        return levels[level];
    }

    public int getCurrentLevelIndex() {
        return level;
    }

    public Config getConfig() {
        return config.copy();
    }

    public void setOnLevelChangeListener(OnLevelChangeListener listener) {
        this.listener = listener;
    }

    // ==================== 核心更新 ====================
    public synchronized boolean update(int bps) {
        return update(bps, System.nanoTime() / 1_000_000);
    }

    public synchronized boolean update(int bps, long nowMs) {
        int oldLevel = level;
        int newLevel = computeTargetLevel(bps);

        boolean isUpgrade = newLevel > oldLevel;
        boolean isDowngrade = newLevel < oldLevel;
        boolean isSevereDowngrade = isDowngrade && (oldLevel - newLevel) >= 2;

        // ---------- 升级路径 ----------
        if (isUpgrade) {
            downgradeSinceMs = -1;
            targetDowngradeLevel = -1;

            long requiredStable = resolveUpgradeStableMs(nowMs);

            if (upgradeSinceMs < 0) {
                upgradeSinceMs = nowMs;
            }
            if (nowMs - upgradeSinceMs < requiredStable) {
                return false;
            }
        } else {
            upgradeSinceMs = -1;
        }

        // ---------- 降级路径 ----------
        if (isDowngrade) {
            upgradeSinceMs = -1;

            if (isSevereDowngrade) {
                // 严重降档：仅受冷却约束，快速响应
                if (lastChangeMs != Long.MIN_VALUE
                        && (nowMs - lastChangeMs < config.severeDowngradeCooldownMs)) {
                    return false;
                }
            } else {
                // 常规单级降档：防抖
                // 优化点：只要目标档位 <= 当前缓存目标位，就继续计时（容忍档位边界抖动）
                if (targetDowngradeLevel < 0 || newLevel < targetDowngradeLevel) {
                    // 目标位下移（更严重）→ 重置计时并更新目标
                    targetDowngradeLevel = newLevel;
                    downgradeSinceMs = nowMs;
                    return false;
                } else if (newLevel == targetDowngradeLevel) {
                    // 同一目标位，继续累计
                    if (nowMs - downgradeSinceMs < config.downgradeStableMs) {
                        return false;
                    }
                } else {
                    // newLevel > targetDowngradeLevel：带宽回升到目标位之上，但仍在降档区间
                    // 这种情况下不执行降档，取消候选
                    targetDowngradeLevel = -1;
                    downgradeSinceMs = -1;
                    return false;
                }
            }
        } else {
            downgradeSinceMs = -1;
            targetDowngradeLevel = -1;
        }

        // ---------- 执行切换 ----------
        if (newLevel != oldLevel) {
            Level oldL = levels[oldLevel];
            Level newL = levels[newLevel];

            lastChangeMs = nowMs;
            upgradeSinceMs = -1;
            downgradeSinceMs = -1;
            targetDowngradeLevel = -1;

            if (isSevereDowngrade) {
                lastSevereDowngradeMs = nowMs;
            }

            level = newLevel;

            if (listener != null) {
                ChangeReason reason = isSevereDowngrade
                        ? ChangeReason.SEVERE_DOWNGRADE
                        : (isUpgrade ? ChangeReason.UPGRADE : ChangeReason.DOWNGRADE);
                listener.onLevelChanged(oldL, newL, reason, nowMs);
            }
            return true;
        }

        return false;
    }

    // ==================== 内部工具 ====================
    private int computeTargetLevel(int bps) {
        int newLevel = level;

        // 降级
        while (newLevel > 0 && bps < levels[newLevel].bitRate * config.downscaleFactor) {
            newLevel--;
        }
        // 升级
        while (newLevel < levels.length - 1
                && bps > levels[newLevel + 1].bitRate * config.upscaleFactor) {
            newLevel++;
        }
        return newLevel;
    }

    /**
     * 快速升档通道：如果最近发生过严重降档，且现在网络快速恢复，
     * 使用更短的升档防抖时间，避免"降得太狠、升得太慢"。
     */
    private long resolveUpgradeStableMs(long nowMs) {
        if (lastSevereDowngradeMs != Long.MIN_VALUE
                && (nowMs - lastSevereDowngradeMs) <= config.fastUpgradeWindowMs) {
            return config.fastUpgradeStableMs;
        }
        return config.upgradeStableMs;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ==================== 辅助判断 ====================
    /**
     * 判断两次 Level 变更是否涉及分辨率或帧率变化。
     * 若任一参数为 null，抛出 IllegalArgumentException（调用方需保证非 null）。
     */
    public static boolean isResolutionOrFpsChanged(Level oldLevel, Level newLevel) {
        if (oldLevel == null || newLevel == null) {
            throw new IllegalArgumentException("Level must not be null");
        }
        return oldLevel.getMaxSize() != newLevel.getMaxSize()
                || oldLevel.getFps() != newLevel.getFps();
    }
}