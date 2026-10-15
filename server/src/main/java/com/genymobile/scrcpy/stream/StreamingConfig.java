package com.genymobile.scrcpy.stream;

import com.genymobile.scrcpy.Options;
import com.genymobile.scrcpy.audio.AudioScheme;
import com.genymobile.scrcpy.signal.PortMapConfig;
import com.genymobile.scrcpy.video.BitrateAggregator;

/** Validated deployment policy. One running server exposes exactly one cloud-phone room. */
public final class StreamingConfig {
    public final int port;
    public final String bindAddress;
    public final String token;
    public final String room;
    public final int maxPeers;
    public final boolean audio;
    public final boolean control;
    public final String turnUrl;
    public final String turnUser;
    public final String turnPassword;
    public final PortMapConfig portMap;
    public final BitrateAggregator.Policy bandwidthPolicy;
    public final int minimumMediaPort;
    public final int maximumMediaPort;

    public StreamingConfig(Options options) {
        if (!options.getSignalDebug() && options.getSignalToken().length() < 32) {
            throw new IllegalArgumentException("Production signaling requires signal_token with at least 32 characters");
        }
        if (options.getAudio() && options.getAudioScheme() != AudioScheme.PCM) {
            throw new IllegalArgumentException("WebRTC supports audio_scheme=pcm; external Opus requires separate qualification");
        }
        if (options.getMaxPeers() < 1 || options.getMaxPeers() > 64) {
            throw new IllegalArgumentException("max_peers must be in 1..64");
        }
        if (options.getSignalPort() < 1 || options.getSignalPort() > 65535) {
            throw new IllegalArgumentException("signal_port must be in 1..65535");
        }
        if (!options.getSignalRoom().matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid signal_room");
        }
        if (!"controller".equals(options.getBandwidthPolicy()) && !"minimum".equals(options.getBandwidthPolicy())) {
            throw new IllegalArgumentException("bandwidth_policy must be controller or minimum");
        }
        bandwidthPolicy = "controller".equals(options.getBandwidthPolicy())
                ? BitrateAggregator.Policy.CONTROLLER : BitrateAggregator.Policy.MINIMUM;
        minimumMediaPort = options.getLocalMinPort();
        maximumMediaPort = options.getLocalMaxPort();
        if (!((minimumMediaPort == 0 && maximumMediaPort == 0)
                || (minimumMediaPort > 0 && maximumMediaPort >= minimumMediaPort && maximumMediaPort <= 65535))) {
            throw new IllegalArgumentException("Media ports must be 0/0 or a valid local_min_port..local_max_port range");
        }
        port = options.getSignalPort();
        bindAddress = options.getSignalBind();
        token = options.getSignalToken();
        room = options.getSignalRoom();
        maxPeers = options.getMaxPeers();
        audio = options.getAudio();
        control = options.getControl();
        turnUrl = options.getTurnUrl();
        turnUser = options.getTurnUser();
        turnPassword = options.getTurnPassword();
        portMap = new PortMapConfig(options.getPublicIp(), options.getLocalMinPort(), options.getLocalMaxPort(),
                options.getExternalMinPort(), options.getExternalMaxPort());
        if (!options.getPublicIp().isEmpty() && !portMap.isEnabled()) {
            throw new IllegalArgumentException("public_ip requires complete local/external port mappings");
        }
    }
}
