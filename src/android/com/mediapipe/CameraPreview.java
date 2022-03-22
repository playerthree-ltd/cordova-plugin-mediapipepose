package com.mediapipe;

// Copyright 2019 The MediaPipe Authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.CameraX;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.glutil.EglManager;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import javax.annotation.Nullable;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * Uses CameraX APIs for camera setup and access.
 *
 * <p>{@link CameraX} connects to the camera and provides video frames.
 */
public class CameraPreview extends CameraHelper {
    /**
     * Provides an Executor that wraps a single-threaded Handler.
     *
     * <p>All operations involving the surface texture should happen in a single thread, and that
     * thread should not be the main thread.
     *
     * <p>The surface provider callbacks require an Executor, and the onFrameAvailable callback
     * requires a Handler. We want everything to run on the same thread, so we need an Executor that
     * is also a Handler.
     */
    private static final class SingleThreadHandlerExecutor implements Executor {

        private final HandlerThread handlerThread;
        private final Handler handler;

        SingleThreadHandlerExecutor(String threadName, int priority) {
            handlerThread = new HandlerThread(threadName, priority);
            handlerThread.start();
            handler = new Handler(handlerThread.getLooper());
        }

        @Override
        public void execute(Runnable command) {
            if (!handler.post(command)) {
                throw new RejectedExecutionException(handlerThread.getName() + " is shutting down.");
            }
        }

        boolean shutdown() {
            return handlerThread.quitSafely();
        }
    }

    private static final String TAG = "CameraPreviewHelper";

    // Target frame and view resolution size in landscape.
    private static final Size TARGET_SIZE = new Size(1280, 720);
    private static final double ASPECT_TOLERANCE = 0.25;
    private static final double ASPECT_PENALTY = 10000;

    private final SingleThreadHandlerExecutor renderExecutor =
            new SingleThreadHandlerExecutor("RenderThread", Process.THREAD_PRIORITY_DEFAULT);

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private Camera camera;
    private int[] textures = null;

    // Size of the camera-preview frames from the camera.
    private Size frameSize;
    // Rotation of the camera-preview frames in degrees.
    private int frameRotation;

    private boolean isPortrait = true;

    @Nullable
    private CameraCharacteristics cameraCharacteristics = null;

    public CameraPreview() {
    }

    public CameraPreview(boolean isPortrait) {
        this.isPortrait = isPortrait;
    }

    /**
     * Initializes the camera and sets it up for accessing frames, using the default 1280 * 720
     * preview size.
     */
    @Override
    public void startCamera(
            Activity activity, CameraFacing cameraFacing, @Nullable SurfaceTexture surfaceTexture) {
        startCamera(activity, (LifecycleOwner) activity, cameraFacing, surfaceTexture, TARGET_SIZE);
    }

    /**
     * Initializes the camera and sets it up for accessing frames.
     *
     * @param targetSize the preview size to use. If set to {@code null}, the helper will default to
     *                   1280 * 720.
     */
    public void startCamera(
            Activity activity,
            CameraFacing cameraFacing,
            @Nullable SurfaceTexture surfaceTexture,
            @Nullable Size targetSize) {
        startCamera(activity, (LifecycleOwner) activity, cameraFacing, surfaceTexture, targetSize);
    }

    /**
     * Initializes the camera and sets it up for accessing frames.
     *
     * @param targetSize a predefined constant {@link #TARGET_SIZE}. If set to {@code null}, the
     *                   helper will default to 1280 * 720.
     */
    @SuppressLint("UnsafeExperimentalUsageError")
    private void startCamera(
            Context context,
            LifecycleOwner lifecycleOwner,
            CameraFacing cameraFacing,
            @Nullable SurfaceTexture surfaceTexture,
            @Nullable Size targetSize) {
        Executor mainThreadExecutor = ContextCompat.getMainExecutor(context);
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(context);
        final boolean isSurfaceTextureProvided = surfaceTexture != null;

        Integer selectedLensFacing =
                cameraFacing == CameraHelper.CameraFacing.FRONT
                        ? CameraMetadata.LENS_FACING_FRONT
                        : CameraMetadata.LENS_FACING_BACK;
        cameraCharacteristics = getCameraCharacteristics(context, selectedLensFacing);
        targetSize = getOptimalViewSize(targetSize);
        // Falls back to TARGET_SIZE if either targetSize is not set or getOptimalViewSize() can't
        // determine the optimal view size.
        if (targetSize == null) {
            targetSize = TARGET_SIZE;
        }

        Size rotatedSize = (isPortrait) ?
                new Size(targetSize.getHeight(), targetSize.getWidth()) :
                new Size(targetSize.getWidth(), targetSize.getHeight());

        cameraProviderFuture.addListener(
                () -> {
                    try {
                        cameraProvider = cameraProviderFuture.get();
                    } catch (Exception e) {
                        if (e instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        Log.e(TAG, "Unable to get ProcessCameraProvider: ", e);
                        return;
                    }

                    preview = new Preview.Builder().setTargetResolution(rotatedSize).build();

                    CameraSelector cameraSelector =
                            cameraFacing == CameraHelper.CameraFacing.FRONT
                                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                                    : CameraSelector.DEFAULT_BACK_CAMERA;

                    // Provide surface texture.
                    preview.setSurfaceProvider(
                            renderExecutor,
                            request -> {
                                frameSize = request.getResolution();
                                Log.d(
                                        TAG,
                                        String.format(
                                                "Received surface request for resolution %dx%d",
                                                frameSize.getWidth(), frameSize.getHeight()));

                                SurfaceTexture previewFrameTexture =
                                        isSurfaceTextureProvided ? surfaceTexture : createSurfaceTexture();
                                previewFrameTexture.setDefaultBufferSize(
                                        frameSize.getWidth(), frameSize.getHeight());

                                request.setTransformationInfoListener(
                                        renderExecutor,
                                        transformationInfo -> {
                                            frameRotation = transformationInfo.getRotationDegrees();

                                            if (!isSurfaceTextureProvided) {
                                                // Detach the SurfaceTexture from the GL context we created earlier so that
                                                // the MediaPipe pipeline can attach it.
                                                // Only needed if MediaPipe pipeline doesn't provide a SurfaceTexture.
                                                previewFrameTexture.detachFromGLContext();
                                            }

                                            OnCameraStartedListener listener = onCameraStartedListener;
                                            if (listener != null) {
                                                ContextCompat.getMainExecutor(context)
                                                        .execute(() -> listener.onCameraStarted(previewFrameTexture));
                                            }
                                        });

                                Surface surface = new Surface(previewFrameTexture);
                                Log.d(TAG, "Providing surface");
                                request.provideSurface(
                                        surface,
                                        renderExecutor,
                                        result -> {
                                            Log.d(TAG, "Surface request result: " + result);
                                            if (textures != null) {
                                                GLES20.glDeleteTextures(1, textures, 0);
                                            }
                                            // Per
                                            // https://developer.android.com/reference/androidx/camera/core/SurfaceRequest.Result,
                                            // the surface was either never used (RESULT_INVALID_SURFACE,
                                            // RESULT_REQUEST_CANCELLED, RESULT_SURFACE_ALREADY_PROVIDED) or the surface
                                            // was used successfully and was eventually detached
                                            // (RESULT_SURFACE_USED_SUCCESSFULLY) so we can release it now to free up
                                            // resources.
                                            if (!isSurfaceTextureProvided) {
                                                previewFrameTexture.release();
                                            }
                                            surface.release();
                                        });
                            });

                    // If we pause/resume the activity, we need to unbind the earlier preview use case, given
                    // the way the activity is currently structured.
                    cameraProvider.unbindAll();
                    camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview);
                },
                mainThreadExecutor);
    }

    @Override
    public boolean isCameraRotated() {
        return frameRotation % 180 == 90;
    }

    @Override
    public Size computeDisplaySizeFromViewSize(Size viewSize) {
        // Camera target size is computed already, so just return the capture frame size.
        return frameSize;
    }

    @Nullable
    private Size getOptimalViewSize(@Nullable Size targetSize) {
        if (targetSize == null || cameraCharacteristics == null) {
            return null;
        }

        StreamConfigurationMap map =
                cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] outputSizes = map.getOutputSizes(SurfaceTexture.class);

        // Find the best matching size. We give a large penalty to sizes whose aspect
        // ratio is too different from the desired one. That way we choose a size with
        // an acceptable aspect ratio if available, otherwise we fall back to one that
        // is close in width.
        Size optimalSize = null;
        double targetRatio = (double) targetSize.getWidth() / targetSize.getHeight();
        Log.d(
                TAG,
                String.format(
                        "Camera target size ratio: %f width: %d", targetRatio, targetSize.getWidth()));
        double minCost = Double.MAX_VALUE;
        for (Size size : outputSizes) {
            double aspectRatio = (double) size.getWidth() / size.getHeight();
            double ratioDiff = Math.abs(aspectRatio - targetRatio);
            double cost =
                    (ratioDiff > ASPECT_TOLERANCE ? ASPECT_PENALTY + ratioDiff * targetSize.getHeight() : 0)
                            + Math.abs(size.getWidth() - targetSize.getWidth());
            Log.d(
                    TAG,
                    String.format(
                            "Camera size candidate width: %d height: %d ratio: %f cost: %f",
                            size.getWidth(), size.getHeight(), aspectRatio, cost));
            if (cost < minCost) {
                optimalSize = size;
                minCost = cost;
            }
        }
        if (optimalSize != null) {
            Log.d(
                    TAG,
                    String.format(
                            "Optimal camera size width: %d height: %d",
                            optimalSize.getWidth(), optimalSize.getHeight()));
        }
        return optimalSize;
    }

    public Size getFrameSize() {
        return frameSize;
    }

    private SurfaceTexture createSurfaceTexture() {
        // Create a temporary surface to make the context current.
        EglManager eglManager = new EglManager(null);
        EGLSurface tempEglSurface = eglManager.createOffscreenSurface(1, 1);
        eglManager.makeCurrent(tempEglSurface, tempEglSurface);
        textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        return new SurfaceTexture(textures[0]);
    }

    @Nullable
    private static CameraCharacteristics getCameraCharacteristics(
            Context context, Integer lensFacing) {
        CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraList = cameraManager.getCameraIdList();
            for (String availableCameraId : cameraList) {
                CameraCharacteristics availableCameraCharacteristics =
                        cameraManager.getCameraCharacteristics(availableCameraId);
                Integer availableLensFacing =
                        availableCameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (availableLensFacing == null) {
                    continue;
                }
                if (availableLensFacing.equals(lensFacing)) {
                    return availableCameraCharacteristics;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "Accessing camera ID info got error: " + e);
        }
        return null;
    }
}