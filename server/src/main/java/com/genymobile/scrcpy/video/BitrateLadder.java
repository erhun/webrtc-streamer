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

    // ==================== 可配置参数 ====================
    // The production tier table lives in VideoAdaptationPolicy.CLOUD_PHONE_LEVELS (single source of truth);
    // BitrateLadder is table-agnostic and every caller must pass an explicit ladder.
    public static final class Config {
        /** 带宽低于当前档码率 * downscaleFactor 时触发降档 */
        public double downscaleFactor = 0.78;
        /** 带宽高于下一档码率 * upscaleFactor 时触发升档 */
        public double upscaleFactor = 1.12;
        /** 升档防抖：需稳定维持的毫秒数 */
        public long upgradeStableMs = 6000L;
        /**
         * 常规降档防抖：需持续低于阈值的毫秒数。码率连续跟随 GoogCC 已即时吸收拥塞（零 codec reset），
         * 分辨率降档是最后手段，必须拉长去抖窗口，避免被 IDR 突发诱发的瞬时塌陷拖入
         * 854→640→480 连环 reset 死亡螺旋。
         */
        public long downgradeStableMs = 8000L;
        /**
         * 严重降档（跨 ≥2 级）需持续维持的毫秒数。同样是最后手段：瞬时塌陷由码率吸收、level 不动，
         * 只有持续数秒的真崩溃才触发一次跨级下调（computeDowngradeLevel 一步到底，单次 reset）。
         */
        public long severeDowngradeStableMs = 5000L;
        /** 档位切换后的最小保持时间：防止短时间内连续切换 */
        public long minHoldAfterChangeMs = 5000L;

        public Config() {}

        public Config copy() {
            Config c = new Config();
            c.downscaleFactor = downscaleFactor;
            c.upscaleFactor = upscaleFactor;
            c.upgradeStableMs = upgradeStableMs;
            c.downgradeStableMs = downgradeStableMs;
            c.severeDowngradeStableMs = severeDowngradeStableMs;
            c.minHoldAfterChangeMs = minHoldAfterChangeMs;
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
    private long lastFeedbackMs = Long.MIN_VALUE;

    private long upgradeSinceMs = -1;
    private long downgradeSinceMs = -1;
    private int targetDowngradeLevel = -1;

    private OnLevelChangeListener listener;

    // ==================== 构造 ====================
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
    /** Invalidate network observations after a pause or peer replacement, without changing the applied tier. */
    public synchronized void resetFeedback() {
        lastFeedbackMs = Long.MIN_VALUE;
        upgradeSinceMs = -1;
        downgradeSinceMs = -1;
        targetDowngradeLevel = -1;
    }

    public synchronized boolean update(int bps) {
        return update(bps, bps, System.nanoTime() / 1_000_000, true);
    }

    public synchronized boolean update(int bps, long nowMs) {
        return update(bps, bps, nowMs, true);
    }

    /**
     * @param rawBps        latest allocation; drives downgrades so congestion is answered immediately.
     * @param smoothedBps   EMA-damped allocation; drives upgrades so a single probe spike cannot promote a tier.
     * @param freshFeedback true only for a new SetRates event. Cached encoder-thread ticks (false) keep a
     *                      downgrade responsive but never advance or commit an upgrade window, so silence
     *                      cannot promote a stale budget.
     */
    public synchronized boolean update(int rawBps, int smoothedBps, long nowMs, boolean freshFeedback) {
        if (rawBps <= 0) {
            resetFeedback();
            return false;
        }
        // SetRates is change-driven, not a heartbeat. Silence does not revoke a budget.
        // A clock regression still invalidates observations; zero/peer reset and lower budgets are handled separately.
        if (lastFeedbackMs != Long.MIN_VALUE && nowMs < lastFeedbackMs) {
            resetFeedback();
            lastChangeMs = Long.MIN_VALUE;
        }
        lastFeedbackMs = nowMs;

        int oldLevel = level;
        int downgradeTarget = computeDowngradeLevel(rawBps);
        boolean isDowngrade = downgradeTarget < oldLevel;
        boolean isSevereDowngrade = isDowngrade && (oldLevel - downgradeTarget) >= 2;
        // Congestion always outranks a pending upgrade: a lower raw budget cancels the upgrade candidate.
        boolean isUpgrade = !isDowngrade && computeUpgradeLevel(smoothedBps) > oldLevel;

        int newLevel = oldLevel;

        // ---------- 升级路径 ----------
        if (isUpgrade) {
            downgradeSinceMs = -1;
            targetDowngradeLevel = -1;

            // A cached tick re-feeds a stale budget; it must never accumulate stability or commit an upgrade.
            if (!freshFeedback) {
                return false;
            }
            if (upgradeSinceMs < 0) {
                upgradeSinceMs = nowMs;
            }
            if (nowMs - upgradeSinceMs < config.upgradeStableMs) {
                return false;
            }
            // Hold and stability run concurrently. Never delay a congestion downgrade with the upgrade hold.
            if (lastChangeMs != Long.MIN_VALUE && nowMs - lastChangeMs < config.minHoldAfterChangeMs) {
                return false;
            }
            newLevel = oldLevel + 1;
        } else {
            upgradeSinceMs = -1;
        }

        // ---------- 降级路径 ----------
        // 分辨率降档现在是质量优化，不再是拥塞响应：编码码率已连续跟随 GoogCC（不重建 codec），
        // 因此单次瞬时尖峰绝不能触发 codec reset。普通与严重降档都要求低预算持续维持；严重塌陷
        // 用更短的窗口，保证真实带宽丢失仍能较快跨级下调。这是 Schmitt 触发的时域半边（值域半边是
        // downscaleFactor vs upscaleFactor），也是消除档位震荡的关键。
        if (isDowngrade) {
            upgradeSinceMs = -1;
            newLevel = downgradeTarget;
            long requiredStableMs = isSevereDowngrade
                    ? config.severeDowngradeStableMs
                    : config.downgradeStableMs;

            // 只要目标档位 <= 当前缓存目标位，就继续计时（容忍档位边界抖动）
            if (targetDowngradeLevel < 0 || newLevel < targetDowngradeLevel) {
                // 目标位下移（更严重）→ 重置计时并更新目标
                targetDowngradeLevel = newLevel;
                downgradeSinceMs = nowMs;
                return false;
            } else if (newLevel == targetDowngradeLevel) {
                // 同一目标位，需持续满足窗口才提交
                if (nowMs - downgradeSinceMs < requiredStableMs) {
                    return false;
                }
            } else {
                // newLevel > targetDowngradeLevel：带宽回升到目标位之上，但仍在降档区间
                // 这种情况下不执行降档，取消候选，避免为一个 blip 重建 codec
                targetDowngradeLevel = -1;
                downgradeSinceMs = -1;
                return false;
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
    /** Multi-level drop driven by the raw budget so real congestion is answered without smoothing lag. */
    private int computeDowngradeLevel(int rawBps) {
        int newLevel = level;
        while (newLevel > 0 && rawBps < levels[newLevel].bitRate * config.downscaleFactor) {
            newLevel--;
        }
        return newLevel;
    }

    /**
     * Single-step promotion driven by the smoothed budget. A lower tier's stability window must never
     * authorize a jump to a higher candidate: each successful upgrade resets the timer, so every next
     * tier needs its own stable window.
     */
    private int computeUpgradeLevel(int smoothedBps) {
        if (level < levels.length - 1
                && smoothedBps > levels[level + 1].bitRate * config.upscaleFactor) {
            return level + 1;
        }
        return level;
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
