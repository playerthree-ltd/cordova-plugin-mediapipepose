Cordova MediaPipe Pose Plugin (WIP)
======

**ANDROID ONLY** 

Cordova plugin for MediaPipe pose classification.

## Usage

Create a new Cordova Project

    $ cordova create pose com.example.poseapp Pose

Install the plugin

    $ cd pose
    $ cordova plugin add https://github.com/playerthree-ltd/cordova-plugin-mediapipepose.git

Add the following code inside `onDeviceReady`

```js
    // to obtain video feed
    Pose.setVideoCallback((base64) => {
        // handle base64 data
    });

    // retrieve landmark data
    Pose.getLandmarks((landmarks) => {
        // handle landmark data
    });

    // predicted label
    Pose.setLabelCallback((label) => {
        console.log(label);
    });
```

Install Android platform

    cordova platform add android

Add options to config.xml

```xml
    <platform name="android">
        <preference name="android-minSdkVersion" value="26"/>
        <preference name="android-targetSdkVersion" value="30"/>

        <!-- permissions -->
        <config-file mode="merge" parent="/*" target="AndroidManifest.xml">
            <uses-permission android:name="android.permission.INTERNET"/>
            <uses-permission android:name="android.permission.CAMERA"/>
            <uses-feature android:name="android.hardware.camera"/>
            <uses-feature android:name="android.hardware.camera.autofocus"/>
        </config-file>

        <!-- camera config -->
        <config-file mode="merge" parent="/manifest/application" target="AndroidManifest.xml">
            <meta-data android:name="cameraFacingFront" android:value="false"/>
            <meta-data android:name="binaryGraphName" android:value="pose_tracking_gpu.binarypb"/>
            <meta-data android:name="inputVideoStreamName" android:value="input_video"/>
            <meta-data android:name="outputVideoStreamName" android:value="output_video"/>
            <meta-data android:name="flipFramesVertically" android:value="True"/>
            <meta-data android:name="converterNumBuffers" android:value="2"/>
        </config-file>
    </platform>
```

**Ensure to add labels.txt and model.tflite files to /model folder in root of project**

Run the code

    cordova run 

## More Info

For more information on setting up Cordova see [the documentation](http://cordova.apache.org/docs/en/latest/guide/cli/index.html)

For more info on plugins see the [Plugin Development Guide](http://cordova.apache.org/docs/en/latest/guide/hybrid/plugins/index.html)

For more info on MediaPipe Pose classification see the [MediaPipe Pose Website](https://google.github.io/mediapipe/solutions/pose.html)
