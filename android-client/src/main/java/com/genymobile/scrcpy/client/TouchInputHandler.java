package com.genymobile.scrcpy.client;

import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

public class TouchInputHandler implements View.OnTouchListener {

    private static final String TAG = "TouchInputHandler";

    public interface Sender {
        boolean send(byte[] payload);
    }

    private final Sender sender;
    private int remoteWidth;
    private int remoteHeight;

    public TouchInputHandler(Sender sender) {
        this.sender = sender;
    }

    public void setRemoteScreenSize(int width, int height) {
        this.remoteWidth = width;
        this.remoteHeight = height;
        Log.i(TAG, "Remote screen: " + width + "x" + height);
    }

    @Override
    public boolean onTouch(View v, MotionEvent event) {
        if (remoteWidth == 0 || remoteHeight == 0) {
            return false;
        }

        int viewWidth = v.getWidth();
        int viewHeight = v.getHeight();
        if (viewWidth == 0 || viewHeight == 0) {
            return false;
        }

        int action = event.getActionMasked();
        int pointerIndex = event.getActionIndex();
        int pointerId = event.getPointerId(pointerIndex);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN:
                sendTouch(v, event, pointerIndex, pointerId, ControlMessageSerializer.ACTION_DOWN);
                return true;

            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    sendTouch(v, event, i, event.getPointerId(i), ControlMessageSerializer.ACTION_MOVE);
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                sendTouch(v, event, pointerIndex, pointerId, ControlMessageSerializer.ACTION_UP);
                return true;

            case MotionEvent.ACTION_CANCEL:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    sendTouch(v, event, i, event.getPointerId(i), ControlMessageSerializer.ACTION_CANCEL);
                }
                return true;

            default:
                return false;
        }
    }

    private void sendTouch(View v, MotionEvent event, int pointerIndex, int pointerId, int action) {
        float viewX = event.getX(pointerIndex);
        float viewY = event.getY(pointerIndex);
        float pressure = event.getPressure(pointerIndex);

        int remoteX = mapCoordinate(viewX, v.getWidth(), remoteWidth);
        int remoteY = mapCoordinate(viewY, v.getHeight(), remoteHeight);

        byte[] payload = ControlMessageSerializer.serializeInjectTouchEvent(
                action, pointerId, remoteX, remoteY, remoteWidth, remoteHeight,
                pressure, 0, 0);

        if (!sender.send(payload)) {
            Log.w(TAG, "Failed to send touch event: action=" + action + " pointer=" + pointerId);
        }
    }

    private int mapCoordinate(float viewCoord, int viewSize, int remoteSize) {
        float ratio = (float) remoteSize / viewSize;
        return Math.round(viewCoord * ratio);
    }
}
