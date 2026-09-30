package com.genymobile.scrcpy.signal;

/**
 * Port mapping configuration for NAT traversal without TURN.
 *
 * When the cloud phone sits behind a NAT with static port mapping,
 * WebRTC ICE candidates carry private IPs/ports that the remote peer
 * cannot reach. This config describes how to rewrite those candidates
 * so the remote peer addresses the public IP with the mapped port.
 *
 * Mapping: local port P → external port = externalMinPort + (P - localMinPort)
 */
public final class PortMapConfig {
    private final String publicIp;
    private final int localMinPort;
    private final int localMaxPort;
    private final int externalMinPort;
    private final int externalMaxPort;

    public PortMapConfig(String publicIp, int localMinPort, int localMaxPort,
                         int externalMinPort, int externalMaxPort) {
        this.publicIp = publicIp;
        this.localMinPort = localMinPort;
        this.localMaxPort = localMaxPort;
        this.externalMinPort = externalMinPort;
        this.externalMaxPort = externalMaxPort;
    }

    public boolean isEnabled() {
        return publicIp != null && !publicIp.isEmpty()
                && localMinPort > 0 && localMaxPort >= localMinPort
                && externalMinPort > 0 && externalMaxPort >= externalMinPort;
    }

    public String getPublicIp() { return publicIp; }
    public int getLocalMinPort() { return localMinPort; }
    public int getLocalMaxPort() { return localMaxPort; }
    public int getExternalMinPort() { return externalMinPort; }
    public int getExternalMaxPort() { return externalMaxPort; }

    /**
     * Compute the external (public) port for a given local port.
     * Returns 0 if the local port is out of range or the computed port exceeds the external range.
     */
    public int getExternalPort(int localPort) {
        if (localPort < localMinPort || localPort > localMaxPort) {
            return 0;
        }
        int offset = localPort - localMinPort;
        int externalPort = externalMinPort + offset;
        if (externalPort > externalMaxPort) {
            return 0;
        }
        return externalPort;
    }
}
