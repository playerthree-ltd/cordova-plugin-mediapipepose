package com.mediapipe;

public interface LabelListenerCallback {
    public void onSuccess(String label);

    public void onFailure(Throwable throwableError);
}