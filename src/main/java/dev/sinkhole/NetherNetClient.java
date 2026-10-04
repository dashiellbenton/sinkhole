package dev.sinkhole;

import dev.onvoid.webrtc.CreateSessionDescriptionObserver;
import dev.onvoid.webrtc.PeerConnectionFactory;
import dev.onvoid.webrtc.PeerConnectionObserver;
import dev.onvoid.webrtc.RTCConfiguration;
import dev.onvoid.webrtc.RTCDataChannel;
import dev.onvoid.webrtc.RTCDataChannelBuffer;
import dev.onvoid.webrtc.RTCDataChannelInit;
import dev.onvoid.webrtc.RTCDataChannelObserver;
import dev.onvoid.webrtc.RTCDataChannelState;
import dev.onvoid.webrtc.RTCIceCandidate;
import dev.onvoid.webrtc.RTCIceGatheringState;
import dev.onvoid.webrtc.RTCOfferOptions;
import dev.onvoid.webrtc.RTCPeerConnection;
import dev.onvoid.webrtc.RTCPeerConnectionState;
import dev.onvoid.webrtc.RTCSdpType;
import dev.onvoid.webrtc.RTCSessionDescription;
import dev.onvoid.webrtc.SetSessionDescriptionObserver;
import dev.onvoid.webrtc.media.audio.AudioDeviceModule;
import dev.onvoid.webrtc.media.audio.AudioLayer;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

/**
 * NetherNet client for Bedrock Dedicated Servers ({@code transport=nethernet}): the SDP offer is POSTed to the server's
 * HTTP signaling endpoint, the answer comes back in the response, and the Bedrock packets then travel over a WebRTC
 * data channel ("ReliableDataChannel"), split into segments of at most 10000 bytes.
 */
public final class NetherNetClient {
    public interface Listener {
        /** One complete (re-assembled) message from the server. Ownership of the buffer passes to the listener. */
        void onMessage(ByteBuf data);

        void onClosed(String reason);
    }

    private static final int MAX_SEGMENT = 10000;

    private final Listener listener;
    private PeerConnectionFactory factory;
    private RTCPeerConnection peer;
    private RTCDataChannel reliable;
    private RTCDataChannel unreliable;
    private volatile boolean closed;

    // reassembly of segmented messages
    private ByteBuf partial;
    private int expectedRemaining = -1;

    public NetherNetClient(Listener listener) {
        this.listener = listener;
    }

    /** Asks a server's HTTP endpoint whether it speaks NetherNet ({@code GET /v1/join} returns a JSON status). */
    public static boolean isNetherNetServer(String host, int port) {
        try {
            HttpResponse<String> r = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().send(
                    HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/v1/join")).timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("protocol");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Connects and blocks until the data channel is open.
     *
     * @param identity optional rewrite of the offer SDP (adds the Xbox identity assertion); may be null
     */
    public void connect(String host, int port, UnaryOperator<String> identity) throws Exception {
        // No audio is ever used; a dummy audio layer also keeps this working on machines without sound hardware.
        factory = new PeerConnectionFactory(new AudioDeviceModule(AudioLayer.kDummyAudio));
        CompletableFuture<Void> gathered = new CompletableFuture<>();
        CompletableFuture<Void> open = new CompletableFuture<>();

        peer = factory.createPeerConnection(new RTCConfiguration(), new PeerConnectionObserver() {
            @Override
            public void onIceCandidate(RTCIceCandidate candidate) {
                // candidates are embedded in the offer once gathering completes (the dedicated server has no trickle ICE)
            }

            @Override
            public void onIceGatheringChange(RTCIceGatheringState state) {
                if (state == RTCIceGatheringState.COMPLETE) {
                    gathered.complete(null);
                }
            }

            @Override
            public void onConnectionChange(RTCPeerConnectionState state) {
                if (state == RTCPeerConnectionState.FAILED || state == RTCPeerConnectionState.CLOSED
                        || state == RTCPeerConnectionState.DISCONNECTED) {
                    open.completeExceptionally(new IllegalStateException("WebRTC connection " + state));
                    closeWith("WebRTC connection " + state.name().toLowerCase());
                }
            }
        });

        RTCDataChannelInit reliableInit = new RTCDataChannelInit();
        reliableInit.ordered = true;
        reliable = peer.createDataChannel("ReliableDataChannel", reliableInit);
        RTCDataChannelInit unreliableInit = new RTCDataChannelInit();
        unreliableInit.ordered = false;
        unreliableInit.maxRetransmits = 0;
        unreliable = peer.createDataChannel("UnreliableDataChannel", unreliableInit);

        reliable.registerObserver(new RTCDataChannelObserver() {
            @Override
            public void onBufferedAmountChange(long previous) {
            }

            @Override
            public void onStateChange() {
                RTCDataChannelState s = reliable.getState();
                if (s == RTCDataChannelState.OPEN) {
                    open.complete(null);
                } else if (s == RTCDataChannelState.CLOSED) {
                    closeWith("data channel closed by the server");
                }
            }

            @Override
            public void onMessage(RTCDataChannelBuffer buffer) {
                handleSegment(buffer.data);
            }
        });

        // offer -> local description
        CompletableFuture<RTCSessionDescription> offer = new CompletableFuture<>();
        peer.createOffer(new RTCOfferOptions(), new CreateSessionDescriptionObserver() {
            @Override
            public void onSuccess(RTCSessionDescription description) {
                offer.complete(description);
            }

            @Override
            public void onFailure(String error) {
                offer.completeExceptionally(new IllegalStateException("create offer: " + error));
            }
        });
        CompletableFuture<Void> localSet = new CompletableFuture<>();
        peer.setLocalDescription(offer.get(10, TimeUnit.SECONDS), observer(localSet, "set local description"));
        localSet.get(10, TimeUnit.SECONDS);
        gathered.get(15, TimeUnit.SECONDS);

        String sdp = peer.getLocalDescription().sdp;
        if (identity != null) {
            sdp = identity.apply(sdp);
        }

        // signaling: POST the offer, the body of the response is the answer
        String networkId = Long.toUnsignedString(new java.util.Random().nextLong());
        HttpResponse<String> response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build().send(
                HttpRequest.newBuilder(URI.create("http://" + host + ":" + port + "/v1/join/" + networkId))
                        .timeout(Duration.ofSeconds(15))
                        .header("Content-Type", "application/sdp")
                        .header("User-Agent", "libhttpclient/1.0.0.0")
                        .POST(HttpRequest.BodyPublishers.ofString(sdp)).build(),
                HttpResponse.BodyHandlers.ofString());
        String body = response.body() == null ? "" : response.body().trim();
        if (response.statusCode() != 200) {
            throw new IllegalStateException("The server's NetherNet signaling answered HTTP " + response.statusCode());
        }
        if (body.isEmpty()) {
            throw new IllegalStateException("The server's NetherNet signaling returned no answer");
        }
        if (body.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException("NetherNet negotiation was refused by the server (error code " + body + ")");
        }

        CompletableFuture<Void> remoteSet = new CompletableFuture<>();
        peer.setRemoteDescription(new RTCSessionDescription(RTCSdpType.ANSWER, body.replace("\r\n", "\n").replace("\n", "\r\n") + (body.endsWith("\n") ? "" : "\r\n")),
                observer(remoteSet, "set remote description"));
        remoteSet.get(10, TimeUnit.SECONDS);

        open.get(20, TimeUnit.SECONDS);
    }

    private static SetSessionDescriptionObserver observer(CompletableFuture<Void> f, String what) {
        return new SetSessionDescriptionObserver() {
            @Override
            public void onSuccess() {
                f.complete(null);
            }

            @Override
            public void onFailure(String error) {
                f.completeExceptionally(new IllegalStateException(what + ": " + error));
            }
        };
    }

    // ------------------------------------------------------------------ messages

    private synchronized void handleSegment(ByteBuffer data) {
        if (data.remaining() < 1) {
            return;
        }
        int remaining = data.get() & 0xFF;
        if (partial == null) {
            partial = Unpooled.buffer();
        } else if (expectedRemaining - 1 != remaining) {
            closeWith("invalid NetherNet segment order");
            return;
        }
        expectedRemaining = remaining;
        partial.writeBytes(data);
        if (remaining == 0) {
            ByteBuf message = partial;
            partial = null;
            expectedRemaining = -1;
            listener.onMessage(message);
        }
    }

    public void send(ByteBuf message) {
        try {
            byte[] bytes = new byte[message.readableBytes()];
            message.readBytes(bytes);
            int segments = Math.max(1, (bytes.length + MAX_SEGMENT - 1) / MAX_SEGMENT);
            synchronized (this) {
                for (int i = 0; i < segments; i++) {
                    int from = i * MAX_SEGMENT;
                    int len = Math.min(MAX_SEGMENT, bytes.length - from);
                    ByteBuffer out = ByteBuffer.allocateDirect(1 + len);
                    out.put((byte) (segments - 1 - i));
                    out.put(bytes, from, len);
                    out.flip();
                    reliable.send(new RTCDataChannelBuffer(out, true));
                }
            }
        } catch (Exception e) {
            closeWith("could not send over the data channel: " + e.getMessage());
        } finally {
            message.release();
        }
    }

    public void close() {
        closeWith(null);
    }

    private void closeWith(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (reliable != null) {
                reliable.unregisterObserver();
                reliable.close();
            }
            if (unreliable != null) {
                unreliable.close();
            }
            if (peer != null) {
                peer.close();
            }
            if (factory != null) {
                factory.dispose();
            }
        } catch (Exception ignored) {
        }
        if (reason != null) {
            listener.onClosed(reason);
        }
    }
}
