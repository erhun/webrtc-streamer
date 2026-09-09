package com.genymobile.scrcpy.video;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.control.PositionMapper;
import com.genymobile.scrcpy.display.DisplayInfo;
import com.genymobile.scrcpy.display.DisplayMonitor;
import com.genymobile.scrcpy.display.DisplayProperties;
import com.genymobile.scrcpy.model.ConfigurationException;
import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.util.LogUtils;
import com.genymobile.scrcpy.wrappers.ServiceManager;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Process;
import android.view.Surface;

import java.io.IOException;
import java.lang.reflect.Method;

public class MediaProjectionCapture extends SurfaceCapture {

    // android.media.projection.MediaProjectionManager.TYPE_SCREEN_CAPTURE (hidden constant)
    private static final int TYPE_SCREEN_CAPTURE = 1;

    private final Context context;
    private final VirtualDisplayListener vdListener;
    private final int displayId;

    private final DisplayMonitor displayMonitor = new DisplayMonitor();

    private VideoConstraints videoConstraints;
    private Size videoSize;
    private Size deviceSize;
    private int dpi;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;

    public MediaProjectionCapture(Context context, VirtualDisplayListener vdListener, Options options) {
        this.context = context;
        this.vdListener = vdListener;
        this.displayId = options.getDisplayId();
    }

    @Override
    protected void init(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        displayMonitor.start(displayId, (props) -> getCaptureControl().reset(CaptureControl.RESET_REASON_DISPLAY_PROPERTIES_CHANGED));
    }

    @Override
    public void prepare() throws ConfigurationException {
        DisplayInfo displayInfo = ServiceManager.getDisplayManager().getDisplayInfo(displayId);
        if (displayInfo == null) {
            Ln.e("Display " + displayId + " not found\n" + LogUtils.buildDisplayListMessage());
            throw new ConfigurationException("Unknown display id: " + displayId);
        }

        deviceSize = displayInfo.getSize();
        int displayRotation = displayInfo.getRotation();
        dpi = displayInfo.getDpi();
        displayMonitor.setSessionDisplayProperties(new DisplayProperties(deviceSize, displayRotation));

        videoSize = deviceSize.constrain(videoConstraints);
    }

    @Override
    public void start(Surface surface) throws IOException {
        if (projection == null) {
            projection = createMediaProjection();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }

        virtualDisplay = projection.createVirtualDisplay(
                "scrcpy", videoSize.getWidth(), videoSize.getHeight(), dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);

        if (vdListener != null) {
            PositionMapper positionMapper = PositionMapper.create(videoSize, null, deviceSize);
            vdListener.onNewVirtualDisplay(displayId, positionMapper);
        }
    }

    @Override
    public void release() {
        displayMonitor.stopAndRelease();

        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (projection != null) {
            projection.stop();
            projection = null;
        }
    }

    @Override
    public Size getSize() {
        return videoSize;
    }

    @Override
    protected boolean applyNewVideoConstraints(VideoConstraints videoConstraints) {
        this.videoConstraints = videoConstraints;
        return true;
    }

    private MediaProjection createMediaProjection() throws IOException {
        MediaProjectionManager manager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            throw new IOException("No MediaProjectionManager");
        }

        try {
            Method method = MediaProjectionManager.class.getMethod(
                    "getMediaProjection", int.class, String.class, int.class, boolean.class);
            MediaProjection mediaProjection = (MediaProjection) method.invoke(
                    manager, Process.myUid(), context.getPackageName(), TYPE_SCREEN_CAPTURE, true);
            if (mediaProjection == null) {
                throw new IOException("getMediaProjection returned null");
            }
            return mediaProjection;
        } catch (ReflectiveOperationException e) {
            throw new IOException("Could not get MediaProjection", e);
        }
    }
}
