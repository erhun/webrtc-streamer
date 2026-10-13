package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.AndroidVersions;
import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.device.Device;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Orientation;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.opengl.AffineOpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLFilter;
import com.genymobile.scrcpy.opengl.OpenGLRunner;
import com.genymobile.scrcpy.util.AffineMatrix;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;
import com.genymobile.scrcpy.wrappers.SurfaceControl;

import android.graphics.Rect;
import android.hardware.display.VirtualDisplay;
import android.os.Build;
import android.os.IBinder;
import android.view.Surface;

import java.io.IOException;

public class ScreenCapture extends SurfaceCapture {

    private final VirtualDisplayListener vdListener;
    private final int displayId;
    private final Rect crop;
    private Orientation.Lock captureOrientationLock;
    private Orientation captureOrientation;
    private final float angle;

    private VideoConstraints videoConstraints;

    private DisplayInfo displayInfo;
    private Size videoSize;

    private final DisplayMonitor displayMonitor = new DisplayMonitor();

    private IBinder display;
    private VirtualDisplay virtualDisplay;
    private Size virtualDisplaySize;

    private AffineMatrix transform;
    private OpenGLRunner glRunner;

    public ScreenCapture(VirtualDisplayListener vdListener, Options options) {
        this.vdListener = vdListener;
        this.displayId = options.getDisplayId();
        assert displayId != Device.DISPLAY_ID_NONE;
        this.crop = options.getCrop();
        this.captureOrientationLock = options.getCaptureOrientationLock();
        this.captureOrientation = options.getCaptureOrientation();
        assert captureOrientationLock != null;
        assert captureOrientation != null;
        this.angle = options.getAngle();
    }

    @Override
    public void init(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        displayMonitor.start(displayId, (props) -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
    }

    @Override
    public void prepare() throws ConfigurationException {
        displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            Ln.e("Display " + displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + displayId);
        }

        if ((displayInfo.getFlags() & DisplayInfo.FLAG_SUPPORTS_PROTECTED_BUFFERS) == 0) {
            Ln.w("Display doesn't have FLAG_SUPPORTS_PROTECTED_BUFFERS flag, mirroring can be restricted");
        }

        Size displaySize = displayInfo.getSize();
        int displayRotation = displayInfo.getRotation();
        displayMonitor.setSessionDisplayProperties(new DisplayProperties(displaySize, displayRotation));

        if (captureOrientationLock == Orientation.Lock.LockedInitial) {
            // The user requested to lock the video orientation to the current orientation
            captureOrientationLock = Orientation.Lock.LockedValue;
            captureOrientation = Orientation.fromRotation(displayRotation);
        }

        VideoFilter filter = new VideoFilter(displaySize);

        if (crop != null) {
            boolean transposed = (displayRotation % 2) != 0;
            filter.addCrop(crop, transposed);
        }

        boolean locked = captureOrientationLock != Orientation.Lock.Unlocked;
        filter.addOrientation(displayRotation, locked, captureOrientation);
        filter.addAngle(angle);

        transform = filter.getInverseTransform();
        videoSize = filter.getOutputSize().constrain(videoConstraints);
    }

    @Override
    public void start(Surface surface) throws IOException {
        Size inputSize = videoSize;
        if (transform != null) {
            inputSize = displayInfo.getSize();
            assert glRunner == null;
            OpenGLFilter glFilter = new AffineOpenGLFilter(transform);
            glRunner = new OpenGLRunner(glFilter);
            glRunner.setMaxFps(videoConstraints.getMaxFps());
            glRunner.setPipelineStats(getPipelineStats());
            surface = glRunner.start(inputSize, videoSize, surface);
        }
        if (getPipelineStats() != null) {
            getPipelineStats().setMode(transform == null ? "direct" : "gl");
        }
        Ln.d("Capture path: " + (transform == null ? "direct" : "gl") + " input=" + inputSize
                + " output=" + videoSize + " maxFps=" + videoConstraints.getMaxFps());

        if (virtualDisplay != null) {
            if (!inputSize.equals(virtualDisplaySize)) {
                virtualDisplay.resize(inputSize.getWidth(), inputSize.getHeight(), displayInfo.getDpi());
                virtualDisplaySize = inputSize;
            }
            virtualDisplay.setSurface(surface);
            Ln.d("Display: reusing existing VirtualDisplay id=" + virtualDisplay.getDisplay().getDisplayId());
        } else if (display != null) {
            Size deviceSize = displayInfo.getSize();
            int layerStack = displayInfo.getLayerStack();
            setDisplaySurface(display, surface, deviceSize.toRect(), inputSize.toRect(), layerStack);
            Ln.d("Display: reusing existing SurfaceControl display");
        } else {
            try {
                int dpi = displayInfo.getDpi();
                int flags = android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR;
                Ln.d("Attempting DisplayManager API with AUTO_MIRROR flag (dpi=" + dpi + ", size=" + inputSize + ")");
                if (displayId == 0) {
                    virtualDisplay = ServiceManager.getDisplayManager()
                            .createNewVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), dpi, surface, flags);
                } else {
                    // AUTO_MIRROR selects the default display. Preserve explicit non-default capture targets.
                    virtualDisplay = ServiceManager.getDisplayManager()
                            .createVirtualDisplay("scrcpy", inputSize.getWidth(), inputSize.getHeight(), displayId, surface);
                }
                virtualDisplaySize = inputSize;
                Ln.d("Display: using DisplayManager API with AUTO_MIRROR flag");
            } catch (Exception displayManagerException) {
                Ln.e("DisplayManager API failed", displayManagerException);
                if (!startWithSurfaceControl(surface, inputSize, 2)) {
                    Ln.e("Could not create display using DisplayManager", displayManagerException);
                    throw new AssertionError("Could not create display: DisplayManager failed and SurfaceControl retry exhausted");
                }
            }
        }

        if (vdListener != null) {
            int virtualDisplayId;
            PositionMapper positionMapper;
            if (virtualDisplay == null || displayId == 0) {
                Size deviceSize = displayInfo.getSize();
                positionMapper = PositionMapper.create(videoSize, transform, deviceSize);
                virtualDisplayId = displayId;
            } else {
                positionMapper = PositionMapper.create(videoSize, transform, inputSize);
                virtualDisplayId = virtualDisplay.getDisplay().getDisplayId();
            }
            vdListener.onNewVirtualDisplay(virtualDisplayId, positionMapper);
        }
    }

    @Override
    public void stop() {
        if (virtualDisplay != null) {
            virtualDisplay.setSurface(null);
            Ln.d("ScreenCapture.stop(): detached surface from VirtualDisplay id=" + virtualDisplay.getDisplay().getDisplayId());
        }
        if (display != null) {
            SurfaceControl.openTransaction();
            try {
                SurfaceControl.setDisplaySurface(display, null);
            } finally {
                SurfaceControl.closeTransaction();
            }
        }
        if (glRunner != null) {
            glRunner.stopAndRelease();
            glRunner = null;
        }
    }

    @Override
    public void release() {
        displayMonitor.stopAndRelease();
        try {
            stop(); // Also cleans a GL runner if start() failed before captureStarted was set.
        } finally {
            if (display != null) {
                Ln.d("ScreenCapture.release(): destroying SurfaceControl display token");
                SurfaceControl.destroyDisplay(display);
                display = null;
            }
            if (virtualDisplay != null) {
                Ln.d("ScreenCapture.release(): releasing VirtualDisplay id=" + virtualDisplay.getDisplay().getDisplayId());
                virtualDisplay.release();
                virtualDisplay = null;
            }
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        if (glRunner != null) {
            glRunner.setMaxFps(videoConstraints.getMaxFps());
        }
        return true;
    }

    @Override
    public boolean controlsFrameRate() {
        return transform != null;
    }

    private static IBinder createDisplay() throws Exception {
        // Since Android 12 (preview), secure displays could not be created with shell permissions anymore.
        // On Android 12 preview, SDK_INT is still R (not S), but CODENAME is "S".
        boolean secure = Build.VERSION.SDK_INT < AndroidVersions.API_30_ANDROID_11 || (Build.VERSION.SDK_INT == AndroidVersions.API_30_ANDROID_11
                && !"S".equals(Build.VERSION.CODENAME));
        return SurfaceControl.createDisplay("scrcpy", secure);
    }

    private static void setDisplaySurface(IBinder display, Surface surface, Rect deviceRect, Rect displayRect, int layerStack) {
        SurfaceControl.openTransaction();
        try {
            SurfaceControl.setDisplaySurface(display, surface);
            SurfaceControl.setDisplayProjection(display, 0, deviceRect, displayRect);
            SurfaceControl.setDisplayLayerStack(display, layerStack);
        } finally {
            SurfaceControl.closeTransaction();
        }
    }

    private boolean startWithSurfaceControl(Surface surface, Size inputSize, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                Ln.w("SurfaceControl retry attempt " + attempt + "/" + maxRetries);
                try {
                    Thread.sleep(200);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            try {
                DisplayInfo freshInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
                if (freshInfo == null) {
                    Ln.e("SurfaceControl: failed to refresh DisplayInfo for display " + displayId);
                    continue;
                }
                displayInfo = freshInfo;

                display = createDisplay();
                Size deviceSize = displayInfo.getSize();
                int layerStack = displayInfo.getLayerStack();
                Ln.d("SurfaceControl: fresh layerStack=" + layerStack + " for display " + displayId);
                setDisplaySurface(display, surface, deviceSize.toRect(), inputSize.toRect(), layerStack);
                Ln.d("Display: using SurfaceControl API (fallback, attempt=" + attempt + ")");
                return true;
            } catch (Exception e) {
                Ln.e("SurfaceControl attempt " + attempt + " failed", e);
                if (display != null) {
                    try {
                        SurfaceControl.destroyDisplay(display);
                    } catch (Exception ignored) {
                    }
                    display = null;
                }
            }
        }
        return false;
    }
}
