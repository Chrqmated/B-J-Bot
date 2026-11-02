package me.creepermaxcz.mcbots.bedrock;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.handler.codec.MessageToMessageEncoder;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.BedrockPeer;
import org.cloudburstmc.protocol.bedrock.PacketDirection;
import org.cloudburstmc.protocol.bedrock.netty.BedrockBatchWrapper;
import org.cloudburstmc.protocol.bedrock.netty.BedrockPacketWrapper;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionCodec;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.CompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.NoopCompression;
import org.cloudburstmc.protocol.bedrock.netty.codec.compression.SimpleCompressionStrategy;
import org.cloudburstmc.protocol.bedrock.netty.codec.packet.BedrockPacketCodec_v3;
import org.cloudburstmc.protocol.bedrock.netty.initializer.BedrockClientInitializer;
import org.cloudburstmc.protocol.common.util.VarInts;

import javax.crypto.SecretKey;
import java.util.List;
import java.util.function.Consumer;

/**
 * Builds the Bedrock pipeline on top of a NetherNet (WebRTC) client channel.
 * <p>
 * It mirrors Geyser's server side NetherNet pipeline: there is no RakNet frame id, every packet is sent as its own
 * batch, compression is off until the network settings and prefixed afterwards (like RakNet protocol 11),
 * and encryption is handled by DTLS, not by the Bedrock protocol.
 */
public class NetherNetClientInitializer extends BedrockClientInitializer {

    private static final CompressionStrategy NOOP_STRATEGY = new SimpleCompressionStrategy(new NoopCompression());

    /** NetherNet compression behaves like RakNet protocol 11. */
    private static final int EQUIVALENT_RAK_VERSION = 11;

    private final Consumer<BedrockClientSession> sessionConsumer;

    public NetherNetClientInitializer(Consumer<BedrockClientSession> sessionConsumer) {
        this.sessionConsumer = sessionConsumer;
    }

    @Override
    protected void preInitChannel(Channel channel) {
        channel.attr(PacketDirection.ATTRIBUTE).set(PacketDirection.SERVER_BOUND);
        channel.pipeline()
                .addLast(FrameCodec.NAME, FrameCodec.INSTANCE)
                .addLast(CompressionCodec.NAME, new CompressionCodec(NOOP_STRATEGY, false));
    }

    @Override
    protected void initPacketCodec(Channel channel) {
        channel.pipeline().addLast("bedrock-packet-codec", new BedrockPacketCodec_v3());
    }

    @Override
    protected void postInitChannel(Channel channel) throws Exception {
        super.postInitChannel(channel);
        channel.pipeline().replace("bedrock-batch-encoder", PacketEncoder.NAME, PacketEncoder.INSTANCE);
        channel.pipeline().addLast(ExceptionLogger.NAME, ExceptionLogger.INSTANCE);
    }

    /**
     * A packet the bot cannot decode only loses that packet, so log it in one line instead of a stack trace.
     */
    @ChannelHandler.Sharable
    static class ExceptionLogger extends io.netty.channel.ChannelInboundHandlerAdapter {
        static final String NAME = "bedrock-bot-exceptions";
        static final ExceptionLogger INSTANCE = new ExceptionLogger();

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Throwable root = cause;
            while (root.getCause() != null && root.getCause() != root) {
                root = root.getCause();
            }
            String message = cause.getMessage() == null ? "" : cause.getMessage();
            int cut = message.indexOf('(');
            me.creepermaxcz.mcbots.Log.warn("Bedrock packet error: " + (cut > 0 ? message.substring(0, cut) : message)
                    + " (" + root.getClass().getSimpleName() + ")");
        }
    }

    @Override
    protected BedrockPeer createPeer(Channel channel) {
        return new BedrockPeer(channel, this::createSession) {
            @Override
            public void enableEncryption(SecretKey secretKey) {
                // The data channel is already encrypted by DTLS
            }

            @Override
            public int getRakVersion() {
                return EQUIVALENT_RAK_VERSION;
            }
        };
    }

    @Override
    protected void initSession(BedrockClientSession session) {
        sessionConsumer.accept(session);
    }

    /**
     * Each NetherNet message is one compressed Bedrock batch.
     */
    @ChannelHandler.Sharable
    static class FrameCodec extends MessageToMessageCodec<ByteBuf, BedrockBatchWrapper> {
        static final String NAME = "nethernet-frame-codec";
        static final FrameCodec INSTANCE = new FrameCodec();

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockBatchWrapper msg, List<Object> out) {
            if (msg.getCompressed() == null) {
                throw new IllegalStateException("Bedrock batch was not compressed");
            }
            out.add(msg.getCompressed().retainedSlice());
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
            if (!msg.isReadable()) {
                return;
            }
            out.add(BedrockBatchWrapper.newInstance(msg.readRetainedSlice(msg.readableBytes()), null));
        }
    }

    /**
     * Sends every packet in its own batch, with its length prefix, instead of grouping them.
     */
    @ChannelHandler.Sharable
    static class PacketEncoder extends MessageToMessageEncoder<BedrockPacketWrapper> {
        static final String NAME = "nethernet-encoder";
        static final PacketEncoder INSTANCE = new PacketEncoder();

        @Override
        protected void encode(ChannelHandlerContext ctx, BedrockPacketWrapper packet, List<Object> out) {
            ByteBuf message = packet.getPacketBuffer();
            if (message == null) {
                throw new IllegalArgumentException("BedrockPacket is not encoded");
            }

            CompositeByteBuf buf = ctx.alloc().compositeDirectBuffer(2);
            BedrockBatchWrapper batch = BedrockBatchWrapper.newInstance();
            try {
                ByteBuf header = ctx.alloc().ioBuffer(5);
                VarInts.writeUnsignedInt(header, message.readableBytes());
                buf.addComponent(true, header);
                buf.addComponent(true, message.retain());
                batch.addPacket(packet.retain());
                batch.setUncompressed(buf.retain());
                out.add(batch.retain());
            } finally {
                buf.release();
                batch.release();
            }
        }
    }
}
