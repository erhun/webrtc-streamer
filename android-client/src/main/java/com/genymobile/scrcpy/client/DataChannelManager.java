package com.genymobile.scrcpy.client;

import android.util.Log;

import org.webrtc.DataChannel;

import java.nio.ByteBuffer;

public class DataChannelManager {

    private static final String TAG = "DataChannelManager";
    private static final String CONTROL_CHANNEL_LABEL = "control";

    public interface Listener {
        void onStateChange(State state);
        void onDeviceMessage(DeviceMessageParser.DeviceMessage message);
    }

    public enum State {
        CONNECTING, OPEN, CLOSING, CLOSED
    }

    private final Listener listener;
    private DataChannel dataChannel;
    private State state = State.CLOSED;

    public DataChannelManager(Listener listener) {
        this.listener = listener;
    }

    public void setDataChannel(DataChannel dc) {
        this.dataChannel = dc;
        dc.registerObserver(new DataChannelObserver());
        updateState(mapState(dc.state()));
        Log.i(TAG, "DataChannel registered: " + dc.label());
    }

    public boolean sendControlMessage(byte[] payload) {
        if (dataChannel == null || state != State.OPEN) {
            Log.w(TAG, "Cannot send: channel=" + dataChannel + " state=" + state);
            return false;
        }
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        DataChannel.Buffer dcBuffer = new DataChannel.Buffer(buffer, false);
        boolean result = dataChannel.send(dcBuffer);
        if (!result) {
            Log.w(TAG, "send() returned false, bufferedAmount=" + dataChannel.bufferedAmount());
        }
        return result;
    }

    public State getState() {
        return state;
    }

    public void dispose() {
        if (dataChannel != null) {
            dataChannel.unregisterObserver();
            dataChannel.close();
            dataChannel.dispose();
            dataChannel = null;
        }
        updateState(State.CLOSED);
        Log.i(TAG, "DataChannel disposed");
    }

    private void updateState(State newState) {
        if (this.state != newState) {
            this.state = newState;
            Log.i(TAG, "State: " + newState.name());
            if (listener != null) {
                listener.onStateChange(newState);
            }
        }
    }

    private State mapState(DataChannel.State dcState) {
        switch (dcState) {
            case CONNECTING:
                return State.CONNECTING;
            case OPEN:
                return State.OPEN;
            case CLOSING:
                return State.CLOSING;
            case CLOSED:
                return State.CLOSED;
            default:
                return State.CLOSED;
        }
    }

    private class DataChannelObserver implements DataChannel.Observer {

        @Override
        public void onBufferedAmountChange(long previousAmount) {
            Log.d(TAG, "bufferedAmount: " + dataChannel.bufferedAmount());
        }

        @Override
        public void onStateChange() {
            updateState(mapState(dataChannel.state()));
        }

        @Override
        public void onMessage(DataChannel.Buffer buffer) {
            ByteBuffer data = buffer.data;
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            DeviceMessageParser.DeviceMessage message = DeviceMessageParser.parse(bytes);
            if (message != null) {
                Log.d(TAG, "DeviceMessage: type=" + message.getType());
                if (listener != null) {
                    listener.onDeviceMessage(message);
                }
            } else {
                Log.w(TAG, "Failed to parse device message, length=" + bytes.length);
            }
        }
    }
}
