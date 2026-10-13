package com.genymobile.scrcpy.opengl;

import com.genymobile.scrcpy.model.Size;
import com.genymobile.scrcpy.util.Threads;
import com.genymobile.scrcpy.util.Ln;
import com.genymobile.scrcpy.video.FrameRateLimiter;
import com.genymobile.scrcpy.video.VideoPipelineStats;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class OpenGLRunner {

    private static HandlerThread handlerThread;
    private static Handler handler;

    private EGLDisplay eglDisplay;
    private EGLContext eglContext;
    private EGLSurface eglSurface;

    private final OpenGLFilter filter;
    private final float[] overrideTransformMatrix;

    private SurfaceTexture surfaceTexture;
    private Surface inputSurface;
    private int textureId;

    private volatile boolean stopped;
    private boolean releaseRequested;
    private final CountDownLatch releaseDone = new CountDownLatch(1);
    private final FrameRateLimiter frameRateLimiter = new FrameRateLimiter();
    private VideoPipelineStats pipelineStats;

    public void setMaxFps(float fps) {
        frameRateLimiter.setMaxFps(fps);
    }

    public void setPipelineStats(VideoPipelineStats stats) {
        pipelineStats = stats;
    }

    public OpenGLRunner(OpenGLFilter filter, float[] overrideTransformMatrix) {
        this.filter = filter;
        this.overrideTransformMatrix = overrideTransformMatrix;
    }

    public OpenGLRunner(OpenGLFilter filter) {
        this(filter, null);
    }

    public static synchronized void initOnce() {
        if (handlerThread == null) {
            handlerThread = new HandlerThread("OpenGLRunner");
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }
    }

    public static void shutdown() throws InterruptedException {
        HandlerThread thread;
        synchronized (OpenGLRunner.class) {
            thread = handlerThread;
            handlerThread = null;
            handler = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread.join();
        }
    }

    public Surface start(Size inputSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        initOnce();

        // The whole OpenGL execution must be performed on a Handler, so that SurfaceTexture.setOnFrameAvailableListener() works correctly.
        // See <https://github.com/Genymobile/scrcpy/issues/5444>
        try {
            Threads.executeSynchronouslyOn(handler, new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    run(inputSize, outputSize, outputSurface);
                    return null;
                }
            });
        } catch (Throwable throwable) {
            if (throwable instanceof OpenGLException) {
                throw (OpenGLException) throwable;
            }
            throw new OpenGLException("Asynchronous OpenGL runner init failed", throwable);
        }

        // Synchronization is ok: inputSurface is written before sem.release() and read after sem.acquire()
        return inputSurface;
    }

    private void run(Size inputSize, Size outputSize, Surface outputSurface) throws OpenGLException {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new OpenGLException("Unable to get EGL14 display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new OpenGLException("Unable to initialize EGL14");
        }

        // @formatter:off
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, configs.length, numConfigs, 0);
        if (numConfigs[0] <= 0) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Unable to find ES2 EGL config");
        }
        EGLConfig eglConfig = configs[0];

        // @formatter:off
        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL context");
        }

        int[] surfaceAttribList = {
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to create EGL window surface");
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            EGL14.eglDestroySurface(eglDisplay, eglSurface);
            EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
            throw new OpenGLException("Failed to make EGL context current");
        }

        int[] minSwap = new int[1];
        EGL14.eglGetConfigAttrib(eglDisplay, eglConfig, EGL14.EGL_MIN_SWAP_INTERVAL, minSwap, 0);
        if (!EGL14.eglSwapInterval(eglDisplay, 0)) {
            Ln.w("GL encoder surface rejected swap interval 0: " + EGL14.eglGetError());
        }
        Ln.d("GL encoder surface: recordable=true requestedSwapInterval=0 minSwapInterval=" + minSwap[0]);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLUtils.checkGlError();
        textureId = textures[0];

        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.checkGlError();
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.checkGlError();

        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setDefaultBufferSize(inputSize.getWidth(), inputSize.getHeight());
        inputSurface = new Surface(surfaceTexture);

        filter.init();

        surfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
            if (stopped) {
                // Make sure to never render after resources have been released
                return;
            }

            render(outputSize);
        }, handler);
    }

    private void render(Size outputSize) {
        GLES20.glViewport(0, 0, outputSize.getWidth(), outputSize.getHeight());
        GLUtils.checkGlError();

        long updateStartNs = System.nanoTime();
        surfaceTexture.updateTexImage();
        if (pipelineStats != null) {
            pipelineStats.onGlInput(System.nanoTime() - updateStartNs);
        }
        long timestampNs = surfaceTexture.getTimestamp();
        // Consume the input even when skipping, so SurfaceFlinger can reuse its raw buffer.
        if (!frameRateLimiter.shouldRender(timestampNs)) {
            if (pipelineStats != null) {
                pipelineStats.onGlSkipped();
            }
            return;
        }

        float[] matrix;
        if (overrideTransformMatrix != null) {
            matrix = overrideTransformMatrix;
        } else {
            matrix = new float[16];
            surfaceTexture.getTransformMatrix(matrix);
        }

        filter.draw(textureId, matrix);

        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, timestampNs);
        long swapStartNs = System.nanoTime();
        if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
            Ln.e("GL encoder swap failed: " + EGL14.eglGetError());
            return;
        }
        if (pipelineStats != null) {
            pipelineStats.onGlSubmitted(System.nanoTime() - swapStartNs);
        }
    }

    public synchronized void stopAndRelease() {
        stopped = true;
        if (!releaseRequested) {
            releaseRequested = true;
            if (!handler.post(() -> {
                try {
                    if (surfaceTexture != null) {
                        surfaceTexture.setOnFrameAvailableListener(null, handler);
                    }
                    filter.release();
                    int[] textures = {textureId};
                    GLES20.glDeleteTextures(1, textures, 0);
                } catch (RuntimeException e) {
                    Ln.e("GL filter cleanup failed", e);
                } finally {
                    try {
                        if (eglDisplay != null && eglDisplay != EGL14.EGL_NO_DISPLAY) {
                            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                                if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) {
                                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                                }
                                if (eglContext != null && eglContext != EGL14.EGL_NO_CONTEXT) {
                                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                                }
                            EGL14.eglTerminate(eglDisplay);
                        }
                        if (surfaceTexture != null) {
                            surfaceTexture.release();
                        }
                        if (inputSurface != null) {
                            inputSurface.release();
                        }
                        } catch (RuntimeException e) {
                            Ln.e("GL surface cleanup failed", e);
                    } finally {
                        releaseDone.countDown();
                    }
                }
            })) {
                releaseDone.countDown();
                throw new IllegalStateException("GL cleanup handler is stopped");
            }
        }

        try {
            if (!releaseDone.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("GL cleanup timed out; inspect swapMax/updateMax before restarting");
            }
        } catch (InterruptedException e) {
            // Behave as if this method call was synchronous
            Thread.currentThread().interrupt();
        }
    }
}
