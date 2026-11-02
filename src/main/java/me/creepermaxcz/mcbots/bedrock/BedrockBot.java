package me.creepermaxcz.mcbots.bedrock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.ScheduledFuture;
import me.creepermaxcz.mcbots.BotClient;
import me.creepermaxcz.mcbots.Log;
import me.creepermaxcz.mcbots.Main;
import org.cloudburstmc.math.vector.Vector2f;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.netty.channel.nethernet.NetherNetChannelFactory;
import org.cloudburstmc.netty.channel.nethernet.NetherNetNative;
import org.cloudburstmc.netty.channel.nethernet.config.NetherChannelOption;
import org.cloudburstmc.netty.channel.nethernet.signaling.ClientInfo;
import org.cloudburstmc.netty.channel.nethernet.signaling.HttpSignalingSettings;
import org.cloudburstmc.netty.channel.nethernet.signaling.NetherNetHTTPClientSignaling;
import org.cloudburstmc.netty.util.nethernet.OperatorIdentity;
import org.cloudburstmc.netty.util.nethernet.TokenTrust;
import org.cloudburstmc.protocol.bedrock.BedrockClientSession;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodecHelper;
import org.cloudburstmc.protocol.bedrock.codec.BedrockPacketSerializer;
import org.cloudburstmc.protocol.bedrock.codec.v2225.Bedrock_v2225;
import org.cloudburstmc.protocol.bedrock.data.ClientPlayMode;
import org.cloudburstmc.protocol.bedrock.data.InputInteractionModel;
import org.cloudburstmc.protocol.bedrock.data.InputMode;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginData;
import org.cloudburstmc.protocol.bedrock.data.command.CommandOriginType;
import org.cloudburstmc.protocol.bedrock.data.definitions.BlockDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.ItemDefinition;
import org.cloudburstmc.protocol.bedrock.data.definitions.SimpleItemDefinition;
import org.cloudburstmc.protocol.bedrock.packet.*;
import org.cloudburstmc.protocol.bedrock.util.EncryptionUtils;
import org.cloudburstmc.protocol.common.DefinitionRegistry;
import org.cloudburstmc.protocol.common.PacketSignal;
import org.jose4j.jwt.consumer.JwtConsumer;
import org.jose4j.jwt.consumer.JwtConsumerBuilder;

import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * A Bedrock Edition bot (26.60) that joins like a real Bedrock client: NetherNet signaling over HTTP, then WebRTC,
 * with an offline (self-signed) login. It is meant for Geyser's built-in signaling.
 */
public class BedrockBot implements BotClient, BedrockPacketHandler {

    /**
     * Bedrock 26.60. Item, recipe and creative inventory data are skipped instead of decoded: the bots never use them,
     * they are big, and decoding them would need Geyser's item palette.
     */
    public static final BedrockCodec CODEC = Bedrock_v2225.CODEC.toBuilder()
            .updateSerializer(ItemComponentPacket.class, skip())
            .updateSerializer(CreativeContentPacket.class, skip())
            .updateSerializer(CraftingDataPacket.class, skip())
            .build();

    private static <T extends BedrockPacket> BedrockPacketSerializer<T> skip() {
        return new BedrockPacketSerializer<>() {
            @Override
            public void serialize(ByteBuf buffer, BedrockCodecHelper helper, T packet) {
                throw new UnsupportedOperationException("Bots never send " + packet.getClass().getSimpleName());
            }

            @Override
            public void deserialize(ByteBuf buffer, BedrockCodecHelper helper, T packet) {
                buffer.skipBytes(buffer.readableBytes());
            }
        };
    }

    private static final int CHUNK_RADIUS = 4;

    private static EventLoopGroup eventLoopGroup;

    private final String nickname;
    private final String xuid;
    private final InetSocketAddress address;
    private final KeyPair keyPair;
    private final UUID commandOriginUuid = UUID.randomUUID();

    private volatile BedrockClientSession session;
    private volatile Channel channel;
    private volatile boolean connected;
    private volatile boolean manualDisconnecting;
    private volatile boolean removed;

    private ScheduledFuture<?> inputTask;
    private long runtimeEntityId;
    private long tick;
    private Vector3f position = Vector3f.ZERO;
    private Vector3f rotation = Vector3f.ZERO;
    private Vector3f pendingDelta = Vector3f.ZERO;

    public BedrockBot(String nickname, InetSocketAddress address) {
        this.nickname = nickname;
        this.address = address;
        this.xuid = BedrockLogin.randomXuid();
        this.keyPair = EncryptionUtils.createKeyPair();

        Log.info("Creating Bedrock bot", nickname);
    }

    /**
     * Loads libdatachannel. Call it once before creating Bedrock bots, to fail early on unsupported platforms.
     */
    public static synchronized void initNative() {
        NetherNetNative.initialize();
        if (eventLoopGroup == null) {
            eventLoopGroup = new MultiThreadIoEventLoopGroup(0,
                    new DefaultThreadFactory("bedrock-bots", true), NioIoHandler.newFactory());
        }
    }

    @Override
    public void start() {
        try {
            initNative();

            // The NetherNet transport identity must be signed with the same key as the login chain:
            // Geyser refuses the login otherwise ("the login chain is signed with a different key ...")
            OperatorIdentity identity = new OperatorIdentity(keyPair.getPrivate(), keyPair.getPublic(), null, "mc-bots")
                    .forPlayer(xuid, nickname);

            HttpSignalingSettings signalingSettings = HttpSignalingSettings.DEFAULT
                    .withClientInfo(new ClientInfo(CODEC.getMinecraftVersion(), CODEC.getProtocolVersion(),
                            BedrockLogin.DEVICE_OS_WINDOWS));

            new Bootstrap()
                    .group(eventLoopGroup)
                    .channelFactory(NetherNetChannelFactory.client(() -> new NetherNetHTTPClientSignaling(signalingSettings)))
                    .option(NetherChannelOption.NETHER_CLIENT_IDENTITY, identity)
                    // Geyser signs with its own self-generated identity, so there is nothing to pin it to
                    .option(NetherChannelOption.NETHER_CLIENT_SERVER_TRUST, ANY_SERVER)
                    .handler(new NetherNetClientInitializer(this::onSessionCreated))
                    .connect(address)
                    .addListener(future -> {
                        if (!future.isSuccess()) {
                            Log.warn("Bedrock bot " + nickname + " could not connect: " + describe(future.cause()));
                            remove();
                        }
                    });
        } catch (Throwable t) {
            Log.error("Bedrock bot " + nickname + " could not start: " + describe(t));
            remove();
        }
    }

    private void onSessionCreated(BedrockClientSession session) {
        this.session = session;
        this.channel = session.getPeer().getChannel();

        session.setCodec(CODEC);
        BedrockCodecHelper helper = session.getPeer().getCodecHelper();
        helper.setItemDefinitions(ANY_ITEM);
        helper.setBlockDefinitions(ANY_BLOCK);
        session.setPacketHandler(this);

        RequestNetworkSettingsPacket request = new RequestNetworkSettingsPacket();
        request.setProtocolVersion(CODEC.getProtocolVersion());
        session.sendPacketImmediately(request);
    }

    // ----------------------------------------------------------------------------------------- login sequence

    @Override
    public PacketSignal handle(NetworkSettingsPacket packet) {
        session.setCompression(packet.getCompressionAlgorithm());
        try {
            session.sendPacketImmediately(BedrockLogin.createLoginPacket(CODEC, keyPair, nickname, xuid,
                    address.getHostString() + ":" + address.getPort()));
        } catch (Exception e) {
            Log.error("Bedrock bot " + nickname + " could not create its login: " + describe(e));
            session.disconnect("Login failed");
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ServerToClientHandshakePacket packet) {
        // Only sent over RakNet, NetherNet is already encrypted by DTLS
        session.sendPacketImmediately(new ClientToServerHandshakePacket());
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePacksInfoPacket packet) {
        // Pretend every pack is already downloaded
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.HAVE_ALL_PACKS);
        session.sendPacketImmediately(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(ResourcePackStackPacket packet) {
        ResourcePackClientResponsePacket response = new ResourcePackClientResponsePacket();
        response.setStatus(ResourcePackClientResponsePacket.Status.COMPLETED);
        session.sendPacketImmediately(response);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(StartGamePacket packet) {
        runtimeEntityId = packet.getRuntimeEntityId();
        position = packet.getPlayerPosition();
        Vector2f rot = packet.getRotation();
        rotation = Vector3f.from(rot.getX(), rot.getY(), rot.getY());
        tick = packet.getCurrentTick();

        RequestChunkRadiusPacket chunkRadius = new RequestChunkRadiusPacket();
        chunkRadius.setRadius(CHUNK_RADIUS);
        chunkRadius.setMaxRadius(CHUNK_RADIUS);
        session.sendPacket(chunkRadius);
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(PlayStatusPacket packet) {
        switch (packet.getStatus()) {
            case LOGIN_SUCCESS -> {
            }
            case PLAYER_SPAWN -> onSpawn();
            default -> Log.warn("Bedrock bot " + nickname + " login refused: " + packet.getStatus());
        }
        return PacketSignal.HANDLED;
    }

    private void onSpawn() {
        if (connected) {
            return;
        }
        SetLocalPlayerAsInitializedPacket initialized = new SetLocalPlayerAsInitializedPacket();
        initialized.setRuntimeEntityId(runtimeEntityId);
        session.sendPacket(initialized);

        connected = true;
        Log.info(nickname + " connected (Bedrock)");

        // A real client sends its input every tick, Geyser translates it to Java movement
        inputTask = channel.eventLoop().scheduleAtFixedRate(this::sendInput, 50, 50, TimeUnit.MILLISECONDS);

        Main.sendJoinMessages(this);
    }

    // ----------------------------------------------------------------------------------------- in game

    @Override
    public PacketSignal handle(MovePlayerPacket packet) {
        if (packet.getRuntimeEntityId() == runtimeEntityId) {
            position = packet.getPosition();
            rotation = packet.getRotation();
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(RespawnPacket packet) {
        if (packet.getState() == RespawnPacket.State.SERVER_READY) {
            position = packet.getPosition();
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(DeathInfoPacket packet) {
        if (Main.autoRespawnDelay >= 0) {
            Log.info("Bot " + nickname + " died. Respawning in " + Main.autoRespawnDelay + " ms.");
            channel.eventLoop().schedule(() -> {
                RespawnPacket respawn = new RespawnPacket();
                respawn.setState(RespawnPacket.State.CLIENT_READY);
                respawn.setRuntimeEntityId(runtimeEntityId);
                respawn.setPosition(Vector3f.ZERO);
                session.sendPacket(respawn);
            }, Main.autoRespawnDelay, TimeUnit.MILLISECONDS);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(NetworkStackLatencyPacket packet) {
        if (packet.isFromServer()) {
            NetworkStackLatencyPacket response = new NetworkStackLatencyPacket();
            response.setTimestamp(packet.getTimestamp());
            response.setFromServer(false);
            session.sendPacket(response);
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public PacketSignal handle(DisconnectPacket packet) {
        if (!manualDisconnecting) {
            Log.info(nickname + " kicked: " + packet.getKickMessage());
        }
        return PacketSignal.HANDLED;
    }

    @Override
    public void onDisconnect(CharSequence reason) {
        boolean wasConnected = connected;
        connected = false;
        if (inputTask != null) {
            inputTask.cancel(false);
        }
        Log.info(nickname + " disconnected");
        if (!manualDisconnecting) {
            Log.info(" -> " + reason + (wasConnected ? "" : " (before spawning)"));
        }
        remove();
    }

    private void sendInput() {
        if (!connected) {
            return;
        }
        Vector3f delta = pendingDelta;
        pendingDelta = Vector3f.ZERO;
        position = position.add(delta);

        PlayerAuthInputPacket input = new PlayerAuthInputPacket();
        input.setRotation(rotation);
        input.setPosition(position);
        input.setMotion(Vector2f.ZERO);
        input.setInputMode(InputMode.MOUSE);
        input.setPlayMode(ClientPlayMode.NORMAL);
        input.setInputInteractionModel(InputInteractionModel.CROSSHAIR);
        input.setInteractRotation(Vector2f.from(rotation.getX(), rotation.getY()));
        input.setTick(++tick);
        input.setDelta(delta);
        input.setAnalogMoveVector(Vector2f.ZERO);
        input.setVehicleRotation(Vector2f.ZERO);
        input.setCameraOrientation(Vector3f.from(0, 0, 1));
        input.setRawMoveVector(Vector2f.ZERO);
        session.sendPacket(input);
    }

    // ----------------------------------------------------------------------------------------- BotClient

    @Override
    public void sendChat(String text) {
        BedrockClientSession session = this.session;
        if (session == null || !connected) {
            return;
        }

        if (text.startsWith("/")) {
            CommandRequestPacket command = new CommandRequestPacket();
            command.setCommand(text);
            command.setCommandOriginData(new CommandOriginData(CommandOriginType.PLAYER, commandOriginUuid, "", 0));
            command.setInternal(false);
            command.setVersion(1);
            session.sendPacket(command);
        } else {
            TextPacket chat = new TextPacket();
            chat.setType(TextPacket.Type.CHAT);
            chat.setNeedsTranslation(false);
            chat.setSourceName(nickname);
            chat.setMessage(text);
            chat.setXuid(xuid);
            chat.setPlatformChatId("");
            chat.setFilteredMessage("");
            session.sendPacket(chat);
        }
    }

    @Override
    public void fallDown() {
        if (connected) {
            channel.eventLoop().execute(() -> pendingDelta = pendingDelta.add(0, -0.5f, 0));
        }
    }

    @Override
    public String getNickname() {
        return nickname;
    }

    @Override
    public Edition getEdition() {
        return Edition.BEDROCK;
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        manualDisconnecting = true;
        BedrockClientSession session = this.session;
        if (session != null) {
            session.disconnect("Leaving");
        } else if (channel != null) {
            channel.close();
        } else {
            remove();
        }
    }

    private void remove() {
        if (!removed) {
            removed = true;
            Main.removeBot(this);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown error";
        }
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() != null ? ": " + root.getMessage() : "");
    }

    /**
     * Accepts any server identity, like {@link TokenTrust#ANY}, but also tokens without an expiration time:
     * Geyser's built-in signaling identity token has none, which {@link TokenTrust#ANY} refuses.
     */
    private static final JwtConsumer ANY_SERVER_CONSUMER = new JwtConsumerBuilder()
            .setDisableRequireSignature()
            .setSkipSignatureVerification()
            .setSkipDefaultAudienceValidation()
            .build();
    private static final TokenTrust ANY_SERVER = identity -> ANY_SERVER_CONSUMER.processToClaims(identity.assertion().token());

    // The bots never look at items or blocks, so any runtime id is accepted instead of loading Geyser's palettes

    private static final DefinitionRegistry<ItemDefinition> ANY_ITEM = new DefinitionRegistry<>() {
        @Override
        public ItemDefinition getDefinition(int runtimeId) {
            return runtimeId == 0 ? ItemDefinition.AIR : new SimpleItemDefinition("unknown:" + runtimeId, runtimeId, false);
        }

        @Override
        public ItemDefinition getDefinition(String identifier) {
            return new SimpleItemDefinition(identifier, 0, false);
        }

        @Override
        public boolean isRegistered(ItemDefinition definition) {
            return true;
        }
    };

    private static final DefinitionRegistry<BlockDefinition> ANY_BLOCK = new DefinitionRegistry<>() {
        @Override
        public BlockDefinition getDefinition(int runtimeId) {
            return () -> runtimeId;
        }

        @Override
        public boolean isRegistered(BlockDefinition definition) {
            return true;
        }
    };
}
