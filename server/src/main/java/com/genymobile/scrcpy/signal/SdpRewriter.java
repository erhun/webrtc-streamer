package com.genymobile.scrcpy.signal;

import com.genymobile.scrcpy.util.Ln;

/**
 * Rewrites ICE candidate SDP to replace private addresses with public mapped addresses.
 *
 * An ICE candidate attribute has the form:
 *   candidate:<foundation> <component> <transport> <priority> <address> <port> typ <type> ...
 *
 * The rewriter extracts the address (token 4) and port (token 5) from the candidate,
 * computes the mapped external port via {@link PortMapConfig}, and replaces all
 * occurrences of the local IP and local port in the SDP with the public values.
 */
public final class SdpRewriter {

    private SdpRewriter() { }

    /**
     * Rewrite an ICE candidate SDP string, replacing local IP:port with public IP:mapped-port.
     * Returns the original SDP unchanged if rewriting is not applicable.
     */
    public static String rewriteIceCandidate(String sdp, PortMapConfig config) {
        if (config == null || !config.isEnabled()) {
            return sdp;
        }
        if (sdp == null || !sdp.contains("typ host")) {
            return sdp;
        }

        String[] tokens = sdp.split(" ");
        if (tokens.length < 6) {
            return sdp;
        }

        String localIp = tokens[4];
        String localPortStr = tokens[5];
        int localPort;
        try {
            localPort = Integer.parseInt(localPortStr);
        } catch (NumberFormatException e) {
            Ln.w("SdpRewriter: cannot parse port from candidate: " + sdp);
            return sdp;
        }

        int externalPort = config.getExternalPort(localPort);
        if (externalPort == 0) {
            Ln.w("SdpRewriter: no mapping for local port " + localPort);
            return sdp;
        }

        String publicIp = config.getPublicIp();
        String externalPortStr = String.valueOf(externalPort);

        Ln.i("SdpRewriter: " + localIp + ":" + localPort + " → " + publicIp + ":" + externalPort);

        String result = sdp;
        result = result.replace(localIp, publicIp);
        result = result.replace(localPortStr, externalPortStr);
        return result;
    }
}
