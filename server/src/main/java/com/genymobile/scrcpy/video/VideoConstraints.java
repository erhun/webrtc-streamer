package com.genymobile.scrcpy.video;

import android.media.MediaCodecInfo;

public class VideoConstraints {
    private final int maxSize;
    private final int alignment;
    private final MediaCodecInfo.VideoCapabilities caps;
    private final float maxFps;

    public VideoConstraints(int maxSize, int alignment, MediaCodecInfo.VideoCapabilities caps) {
        this(maxSize, alignment, caps, 0f);
    }

    public VideoConstraints(int maxSize, int alignment, MediaCodecInfo.VideoCapabilities caps, float maxFps) {
        assert maxSize >= 0 : "Max size must not be negative";
        this.maxSize = maxSize;

        assert alignment > 0 : "Alignment must be positive";
        assert (alignment & (alignment - 1)) == 0 : "Alignment must be a power-of-two";
        this.alignment = alignment;

        assert maxFps >= 0 : "Max fps must not be negative";
        this.maxFps = maxFps;

        this.caps = caps;
    }

    /**
     * Return the max size (0 if not requested)
     *
     * @return the max requested size
     */
    public int getMaxSize() {
        return maxSize;
    }

    /**
     * Return the video alignment.
     * <p>
     * This a power-of-2 value that the video width and height must be multiples of.
     *
     * @return the video alignment
     */
    public int getAlignment() {
        return alignment;
    }

    /**
     * Return the video encoder capabilities.
     *
     * @return the video encoder capabilities
     */
    public MediaCodecInfo.VideoCapabilities getEncoderCapabilities() {
        return caps;
    }

    /**
     * Return the max fps (0 if not requested).
     *
     * @return the max requested fps
     */
    public float getMaxFps() {
        return maxFps;
    }

    /**
     * Return the video constraints with the provided max size.
     *
     * @param maxSize the max requested size
     * @return the new video constraints
     */
    public VideoConstraints withMaxSize(int maxSize) {
        return new VideoConstraints(maxSize, alignment, caps, maxFps);
    }

    /**
     * Return the video constraints with the provided video capabilities.
     *
     * @param caps the video encoder capabilities
     * @return the new video constraints
     */
    public VideoConstraints withCapabilities(MediaCodecInfo.VideoCapabilities caps) {
        return new VideoConstraints(maxSize, alignment, caps, maxFps);
    }

    /**
     * Return the video constraints with the provided max fps.
     *
     * @param maxFps the max requested fps
     * @return the new video constraints
     */
    public VideoConstraints withMaxFps(float maxFps) {
        return new VideoConstraints(maxSize, alignment, caps, maxFps);
    }

    @Override
    public String toString() {
        return "VideoConstraints{maxSize=" + maxSize
                + ", alignment=" + alignment
                + ", maxFps=" + maxFps
                + ", caps=" + (caps != null ? "yes" : "null") + "}";
    }
}