package com.mediapipe;

public interface VideoListenerCallback {
    public void onSuccess(String videoData);

    public void onFailure(Throwable throwableError);
}
