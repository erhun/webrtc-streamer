package com.genymobile.scrcpy.client;

import android.content.Context;
import android.util.Log;

import org.webrtc.AudioTrack;
import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WebRtcManager {

    private static final String TAG = "WebRtcManager";

    public interface Listener {
        void onIceConnectionState(IceConnectionState state);
        void onRemoteVideo(VideoTrack videoTrack);
        void onLocalIceCandidate(IceCandidate candidate);
        void onLocalDataChannel(DataChannel dataChannel);
        void onError(String message);
    }

    public enum IceConnectionState {
        CONNECTING, CONNECTED, DISCONNECTED, FAILED, CLOSED
    }

    private final Context context;
    private final Listener listener;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private VideoTrack remoteVideoTrack;
    private AudioTrack remoteAudioTrack;
    private final List<IceCandidate> pendingIceCandidates = new ArrayList<>();
    private boolean offerCreated;

    public WebRtcManager(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void initialize() {
        PeerConnectionFactory.InitializationOptions options =
                PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(options);

        factory = PeerConnectionFactory.builder()
                .createPeerConnectionFactory();
        Log.i(TAG, "PeerConnectionFactory initialized");
    }

    public boolean isInitialized() {
        return factory != null;
    }

    public void createPeerConnection(List<PeerConnection.IceServer> iceServers) {
        PeerConnection.RTCConfiguration config = new PeerConnection.RTCConfiguration(iceServers);
        config.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        config.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE;

        peerConnection = factory.createPeerConnection(config, new PeerConnectionObserver());

        DataChannel.Init dcInit = new DataChannel.Init();
        dcInit.ordered = true;
        DataChannel dataChannel = peerConnection.createDataChannel("control", dcInit);
        Log.i(TAG, "Control DataChannel created");
        listener.onLocalDataChannel(dataChannel);

        Log.i(TAG, "PeerConnection created");
    }

    public void createPeerConnection() {
        createPeerConnection(Collections.emptyList());
    }

    public void createOffer() {
        createOfferInternal(false);
    }

    public void createIceRestartOffer() {
        createOfferInternal(true);
    }

    private void createOfferInternal(boolean iceRestart) {
        if (peerConnection == null) {
            listener.onError("PeerConnection not created");
            return;
        }
        MediaConstraints constraints = new MediaConstraints();
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        constraints.mandatory.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        if (iceRestart) {
            constraints.mandatory.add(new MediaConstraints.KeyValuePair("IceRestart", "true"));
        }

        peerConnection.createOffer(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {
                Log.i(TAG, "Offer created, type=" + sdp.type.name() + " iceRestart=" + iceRestart);
                peerConnection.setLocalDescription(new SdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sdp) {}
                    @Override
                    public void onSetSuccess() {
                        offerCreated = true;
                    }
                    @Override
                    public void onCreateFailure(String error) {}
                    @Override
                    public void onSetFailure(String error) {
                        Log.e(TAG, "setLocalDescription(offer) failed: " + error);
                        listener.onError("SDP setup failed: " + error);
                    }
                }, sdp);
            }

            @Override
            public void onSetSuccess() {}

            @Override
            public void onCreateFailure(String error) {
                Log.e(TAG, "createOffer failed: " + error);
                listener.onError("Create offer failed: " + error);
            }

            @Override
            public void onSetFailure(String error) {
                Log.e(TAG, "setLocalDescription failed: " + error);
                listener.onError("SDP setup failed: " + error);
            }
        }, constraints);
    }

    public String getOfferSdp() {
        if (peerConnection == null || peerConnection.getLocalDescription() == null) {
            return null;
        }
        return peerConnection.getLocalDescription().description;
    }

    public boolean isOfferCreated() {
        return offerCreated;
    }

    public void setRemoteAnswer(String sdp) {
        if (peerConnection == null) {
            return;
        }
        SessionDescription answer = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        peerConnection.setRemoteDescription(new SdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sdp) {}
            @Override
            public void onSetSuccess() {
                Log.i(TAG, "Remote answer applied");
                flushPendingIceCandidates();
            }
            @Override
            public void onCreateFailure(String error) {}
            @Override
            public void onSetFailure(String error) {
                Log.e(TAG, "setRemoteDescription(answer) failed: " + error);
                listener.onError("Set answer failed: " + error);
            }
        }, answer);
    }

    public void addIceCandidate(String sdpMid, int sdpMLineIndex, String candidate) {
        IceCandidate iceCandidate = new IceCandidate(sdpMid, sdpMLineIndex, candidate);
        if (peerConnection != null && peerConnection.getRemoteDescription() != null) {
            peerConnection.addIceCandidate(iceCandidate);
        } else {
            synchronized (pendingIceCandidates) {
                pendingIceCandidates.add(iceCandidate);
            }
        }
    }

    public void closePeerConnection() {
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        remoteVideoTrack = null;
        remoteAudioTrack = null;
        offerCreated = false;
        synchronized (pendingIceCandidates) {
            pendingIceCandidates.clear();
        }
        Log.i(TAG, "PeerConnection closed");
    }

    public void shutdown() {
        closePeerConnection();
        if (factory != null) {
            factory.dispose();
            factory = null;
        }
        Log.i(TAG, "WebRtcManager shut down");
    }

    private void flushPendingIceCandidates() {
        synchronized (pendingIceCandidates) {
            for (IceCandidate candidate : pendingIceCandidates) {
                peerConnection.addIceCandidate(candidate);
            }
            pendingIceCandidates.clear();
        }
    }

    private class PeerConnectionObserver implements PeerConnection.Observer {

        @Override
        public void onSignalingChange(PeerConnection.SignalingState newState) {
            Log.d(TAG, "signalingState: " + newState.name());
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState newState) {
            Log.i(TAG, "iceConnectionState: " + newState.name());
            switch (newState) {
                case CHECKING:
                case NEW:
                    listener.onIceConnectionState(IceConnectionState.CONNECTING);
                    break;
                case CONNECTED:
                    listener.onIceConnectionState(IceConnectionState.CONNECTED);
                    break;
                case DISCONNECTED:
                    listener.onIceConnectionState(IceConnectionState.DISCONNECTED);
                    break;
                case FAILED:
                    listener.onIceConnectionState(IceConnectionState.FAILED);
                    listener.onError("ICE connection failed");
                    break;
                case CLOSED:
                    listener.onIceConnectionState(IceConnectionState.CLOSED);
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onIceConnectionReceivingChange(boolean receiving) {
            Log.d(TAG, "iceConnectionReceiving: " + receiving);
        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState newState) {
            Log.d(TAG, "iceGatheringState: " + newState.name());
        }

        @Override
        public void onIceCandidate(IceCandidate candidate) {
            Log.d(TAG, "localIceCandidate: mid=" + candidate.sdpMid);
            listener.onLocalIceCandidate(candidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] candidates) {
            Log.d(TAG, "iceCandidatesRemoved");
        }

        @Override
        public void onAddStream(MediaStream stream) {
            Log.i(TAG, "onAddStream: videoTracks=" + stream.videoTracks.size());
            if (!stream.videoTracks.isEmpty()) {
                remoteVideoTrack = stream.videoTracks.get(0);
                remoteVideoTrack.setEnabled(true);
                listener.onRemoteVideo(remoteVideoTrack);
            }
            if (!stream.audioTracks.isEmpty()) {
                remoteAudioTrack = stream.audioTracks.get(0);
                remoteAudioTrack.setEnabled(true);
            }
        }

        @Override
        public void onRemoveStream(MediaStream stream) {
            Log.i(TAG, "onRemoveStream");
        }

        @Override
        public void onDataChannel(DataChannel dc) {
            Log.i(TAG, "onDataChannel: " + dc.label() + " state=" + dc.state());
        }

        @Override
        public void onRenegotiationNeeded() {
            Log.d(TAG, "onRenegotiationNeeded");
        }

        @Override
        public void onAddTrack(RtpReceiver receiver, MediaStream[] streams) {
            Log.d(TAG, "onAddTrack: " + receiver.id());
        }
    }
}
