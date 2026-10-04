package dev.sinkhole;

import io.netty.buffer.ByteBuf;

/** Connects to a NetherNet dedicated server and reports whether the data channel opens. Usage: NetherNetProbe host port */
public final class NetherNetProbe {
    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "127.0.0.1";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 19132;
        System.out.println("isNetherNetServer=" + NetherNetClient.isNetherNetServer(host, port));
        NetherNetClient c = new NetherNetClient(new NetherNetClient.Listener() {
            @Override
            public void onMessage(ByteBuf data) {
                System.out.println("message " + data.readableBytes() + " bytes");
                data.release();
            }

            @Override
            public void onClosed(String reason) {
                System.out.println("closed: " + reason);
            }
        });
        long t = System.currentTimeMillis();
        var key = org.cloudburstmc.protocol.bedrock.util.EncryptionUtils.createKeyPair();
        c.connect(host, port, NetherNetIdentity.assertion(key, null, NetherNetIdentity.DEFAULT_DOMAIN));
        System.out.println("data channel OPEN after " + (System.currentTimeMillis() - t) + " ms");
        Thread.sleep(2000);
        c.close();
        System.exit(0);
    }
}
