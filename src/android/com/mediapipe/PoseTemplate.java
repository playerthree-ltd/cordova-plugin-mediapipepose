/**
 *
 */
package com.mediapipe;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.apache.cordova.PluginResult.Status;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.SurfaceTexture;

import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.View;
import android.view.SurfaceHolder;
import android.view.ViewGroup;
import android.graphics.Bitmap;

import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.ProtoUtil;
import com.google.mediapipe.glutil.EglManager;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.TensorProcessor;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

// super hacky, need to edit this to be the correct package name using a hook on plugin install
import <change_me_8IAnXxPstw>.R;
import <change_me_8IAnXxPstw>.ml.Model;

public class Pose extends CordovaPlugin {
    private static final String TAG = "Pose";
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "pose_landmarks";

    // Flips the camera-preview frames vertically by default, before sending them into FrameProcessor
    // to be processed in a MediaPipe graph, and flips the processed frames back when they are
    // displayed. This maybe needed because OpenGL represents images assuming the image origin is at
    // the bottom-left corner, whereas MediaPipe in general assumes the image origin is at the
    // top-left corner.
    // NOTE: use "flipFramesVertically" in manifest metadata to override this behavior.
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    // Number of output frames allocated in ExternalTextureConverter.
    // NOTE: use "converterNumBuffers" in manifest metadata to override number of buffers. For
    // example, when there is a FlowLimiterCalculator in the graph, number of buffers should be at
    // least `max_in_flight + max_in_queue + 1` (where max_in_flight and max_in_queue are used in
    // FlowLimiterCalculator options). That's because we need buffers for all the frames that are in
    // flight/queue plus one for the next frame from the camera.
    private static final int NUM_BUFFERS = 2;

    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
        System.loadLibrary("tensorflowlite_jni");
        try {
            System.loadLibrary("opencv_java3");
        } catch (java.lang.UnsatisfiedLinkError e) {
            // Some example apps (e.g. template matching) require OpenCV 4.
            System.loadLibrary("opencv_java4");
        }
    }

    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    protected FrameProcessor processor;
    // Handles camera access via the {@link CameraX} Jetpack support library.
    protected CameraPreview cameraHelper;

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private SurfaceTexture previewFrameTexture;
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private SurfaceView previewDisplayView;

    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private ExternalTextureConverter converter;

    // ApplicationInfo for retrieving metadata defined in the manifest.
    private ApplicationInfo applicationInfo;

    private NormalizedLandmarkList poseLandmarks;

    private Activity cordovaActivity;

    private Model model;

    private TensorBuffer inputFeature0;

    private List<String> associatedAxisLabels = null;

    private final float[] inputArray = new float[33 * 4]; // todo - dont use hard coded buffer size here

    private TensorProcessor probabilityProcessor;

    private LabelListenerCallback labelCallBack = null;

    private Bitmap bitmap;

    private Size previewFrame = null;

    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);

        cordovaActivity = this.cordova.getActivity();
        Context context = cordovaActivity.getApplicationContext();

        cordovaActivity.setContentView(R.layout.activity_main);

        ProtoUtil.registerTypeName(NormalizedLandmarkList.class, "mediapipe.NormalizedLandmarkList");

        try {
            applicationInfo = context.getPackageManager().getApplicationInfo(context.getPackageName(), PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        try {
            previewFrame = new Size(applicationInfo.metaData.getInt("previewWidth"), applicationInfo.metaData.getInt("previewHeight"));
            if (previewFrame.getWidth() == 0 || previewFrame.getHeight() == 0) {
                previewFrame = null; // nullify if zero
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        previewDisplayView = new SurfaceView(context);
        setupPreviewDisplayView();

        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(context);
        eglManager = new EglManager(null);
        processor =
                new FrameProcessor(
                        context,
                        eglManager.getNativeContext(),
                        applicationInfo.metaData.getString("binaryGraphName"),
                        applicationInfo.metaData.getString("inputVideoStreamName"),
                        applicationInfo.metaData.getString("outputVideoStreamName"));
        processor
                .getVideoSurfaceOutput()
                .setFlipY(applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));

        PermissionHelper.checkAndRequestCameraPermissions(cordovaActivity);

        Log.d(TAG, "Initializing Pose");

        try {
            String ASSOCIATED_AXIS_LABELS = "labels.txt";
            associatedAxisLabels = FileUtil.loadLabels(context, ASSOCIATED_AXIS_LABELS);
        } catch (IOException e) {
            Log.e("tfliteSupport", "Error reading label file", e);
        }

        int[] bufferShape = null;
        try {
            Resources res = cordovaActivity.getResources();
            bufferShape = res.getIntArray(R.array.bufferShape);
        } catch (Exception e) {
            e.printStackTrace();
        }

        // create tf model
        try {
            model = Model.newInstance(context);

            // Creates inputs for reference.
            if (bufferShape != null) {
                inputFeature0 = TensorBuffer.createFixedSize(bufferShape, DataType.FLOAT32);
            } else {
                inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 33, 4}, DataType.FLOAT32);
            }
        } catch (IOException e) {
            // TODO Handle the exception
            e.printStackTrace();
        }

        // Post-processor which dequantize the result
        probabilityProcessor = new TensorProcessor.Builder().add(new NormalizeOp(0, 255)).build();

        processor.addPacketCallback(
                OUTPUT_LANDMARKS_STREAM_NAME,
                this::onPoseResult);
    }

    /**
     * Called when the activity is becoming visible to the user.
     */
    public void onStart() {
        converter =
                new ExternalTextureConverter(
                        eglManager.getContext(),
                        applicationInfo.metaData.getInt("converterNumBuffers", NUM_BUFFERS));
        converter.setFlipY(
                applicationInfo.metaData.getBoolean("flipFramesVertically", FLIP_FRAMES_VERTICALLY));
        converter.setConsumer(processor);
        if (PermissionHelper.cameraPermissionsGranted(cordovaActivity)) {
            startCamera();
        }
    }

    /**
     * Called when the activity is no longer visible to the user.
     */
    public void onStop() {
        converter.close();

        // Releases model resources if no longer used.
        model.close();

        // Hide preview display until we re-open the camera again.
        previewDisplayView.setVisibility(View.GONE);
    }

    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "getLandmarks":
                if (poseLandmarks != null) {
                    cordova.getThreadPool().execute((Runnable) () -> {
                        final PluginResult result = new PluginResult(PluginResult.Status.OK, (getPoseLandmarks(this.poseLandmarks).toString()));
                        callbackContext.sendPluginResult(result);
                    });
                }
                break;
            case "getLandmarksDebugString":
                final PluginResult result = new PluginResult(PluginResult.Status.OK, (getPoseLandmarksDebugString(poseLandmarks)));
                callbackContext.sendPluginResult(result);
                break;
            case "getVideoFrame":
                cordova.getThreadPool().execute((Runnable) () -> requestVideoFrame(previewDisplayView, callbackContext));
                break;
            case "setLabelCallback":
                setLabelListener(new LabelListenerCallback() {
                    @Override
                    public void onSuccess(String label) {
                        final PluginResult result = new PluginResult(PluginResult.Status.OK, (label));
                        result.setKeepCallback(true);
                        callbackContext.sendPluginResult(result);
                    }

                    @Override
                    public void onFailure(Throwable throwableError) {
                        final PluginResult result = new PluginResult(PluginResult.Status.OK, (throwableError.toString()));
                        callbackContext.sendPluginResult(result);
                    }
                });
                break;
        }

        return true;
    }

    private static JSONArray getPoseLandmarks(NormalizedLandmarkList poseLandmarks) {
        JSONArray result = new JSONArray();
        try {
            for (NormalizedLandmark landmark : poseLandmarks.getLandmarkList()) {
                JSONObject landmarkData = new JSONObject();

                landmarkData.put("x", landmark.getX());
                landmarkData.put("y", landmark.getY());
                landmarkData.put("z", landmark.getZ());
                landmarkData.put("visibility", landmark.getVisibility());
                landmarkData.put("presence", landmark.getPresence());

                result.put(landmarkData);
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return result;
    }

    private static String getPoseLandmarksDebugString(NormalizedLandmarkList poseLandmarks) {
        StringBuilder poseLandmarkStr = new StringBuilder("Pose landmarks: " + poseLandmarks.getLandmarkCount() + "\n");
        int landmarkIndex = 0;
        for (NormalizedLandmark landmark : poseLandmarks.getLandmarkList()) {
            poseLandmarkStr.append("\tLandmark [").append(landmarkIndex).append("]: (").append(landmark.getX()).append(", ").append(landmark.getY()).append(", ").append(landmark.getZ()).append(")\n");
            ++landmarkIndex;
        }
        return poseLandmarkStr.toString();
    }

    public void setLabelListener(LabelListenerCallback callBack) {
        labelCallBack = callBack;
    }

    private void requestVideoFrame(SurfaceView view, CallbackContext callbackContext) {
        PixelCopy.request(view,
                bitmap,
                copyResult -> {
                    if (copyResult == PixelCopy.SUCCESS) {
                        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
                        byte[] byteArray = outputStream.toByteArray();
                        String data = Base64.encodeToString(byteArray, Base64.DEFAULT);
                        final PluginResult result = new PluginResult(PluginResult.Status.OK, ("data:image/png;base64," + data));
                        callbackContext.sendPluginResult(result);
                    } else {
                        // don't send error if we are just waiting for the surface to be ready
                        if (copyResult != PixelCopy.ERROR_SOURCE_NO_DATA) {
                            final PluginResult result = new PluginResult(PluginResult.Status.OK, ("error"));
                            callbackContext.sendPluginResult(result);
                        } else {
                            final PluginResult result = new PluginResult(PluginResult.Status.OK, ("wait"));
                            callbackContext.sendPluginResult(result);
                        }
                    }
                },
                view.getHandler()
        );
    }

    protected Size cameraTargetResolution() {
        return previewFrame;
    }

    //start the camera
    public void startCamera() {
        if (cordovaActivity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            cameraHelper = new CameraPreview(false);
        } else {
            cameraHelper = new CameraPreview(true);
        }

        previewFrameTexture = converter.getSurfaceTexture();
        cameraHelper.setOnCameraStartedListener(
                surfaceTexture -> {
                    onCameraStarted(surfaceTexture);
                    if (previewFrame != null) {
                        bitmap = Bitmap.createBitmap(previewFrame.getWidth(), previewFrame.getHeight(), Bitmap.Config.RGB_565);
                    } else {
                        Size frame = cameraHelper.getFrameSize();
                        bitmap = Bitmap.createBitmap(frame.getWidth(), frame.getHeight(), Bitmap.Config.RGB_565);
                    }
                });

        CameraHelper.CameraFacing cameraFacing =
                applicationInfo.metaData.getBoolean("cameraFacingFront", false)
                        ? CameraHelper.CameraFacing.FRONT
                        : CameraHelper.CameraFacing.BACK;
        cameraHelper.startCamera(cordovaActivity, cameraFacing, previewFrameTexture, cameraTargetResolution());
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();

        // Configure the output width and height as the computed display size.
        converter.setDestinationSize(
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
    }

    private void onPoseResult(Packet packet) {
        try {
            poseLandmarks =
                    PacketGetter.getProto(packet, NormalizedLandmarkList.class);

            // copy landmark data into array
            int arrIndex = 0;
            for (NormalizedLandmark landmark : poseLandmarks.getLandmarkList()) {
                inputArray[arrIndex] = landmark.getX();
                inputArray[arrIndex + 1] = landmark.getY();
                inputArray[arrIndex + 2] = landmark.getZ();
                inputArray[arrIndex + 3] = landmark.getVisibility();
                arrIndex += 4;
            }

            inputFeature0.loadArray(inputArray);
            Model.Outputs outputs = model.process(inputFeature0);

            TensorBuffer buffer = outputs.getOutputFeature0AsTensorBuffer();

            TensorLabel labels = new TensorLabel(associatedAxisLabels, probabilityProcessor.process(buffer));
            Map<String, Float> floatMap = labels.getMapWithFloatValue();

            Map.Entry<String, Float> maxEntry = null;
            for (Map.Entry<String, Float> entry : floatMap.entrySet()) {
                if (maxEntry == null || entry.getValue().compareTo(maxEntry.getValue()) > 0) {
                    maxEntry = entry;
                }
            }

            if (labelCallBack != null) {
                labelCallBack.onSuccess(maxEntry.getKey());
            }

        } catch (InvalidProtocolBufferException exception) {
            Log.e(TAG, "Failed to get proto.", exception);

            if (labelCallBack != null) {
                labelCallBack.onFailure(new Throwable(exception));
            }
        }
    }

    private void setupPreviewDisplayView() {
        previewDisplayView.setVisibility(View.GONE);

        ViewGroup viewGroup = cordovaActivity.findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                                viewGroup.addView(webView.getView());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                onPreviewDisplaySurfaceChanged(holder, format, width, height);
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });
    }
}