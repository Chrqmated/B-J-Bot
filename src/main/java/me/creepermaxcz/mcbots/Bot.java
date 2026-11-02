package me.creepermaxcz.mcbots;

import org.geysermc.mcprotocollib.auth.SessionService;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.UnexpectedEncryptionException;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerCombatKillPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.BitSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Java Edition bot (MC 26.2), connecting over TCP with MCProtocolLib.
 */
public class Bot extends Thread implements BotClient {

    private static final Pattern REASON_CONTENT = Pattern.compile("content=\"(.*?)\"");

    private final String nickname;
    private final ClientSession client;
    private boolean hasMainListener;

    private double lastX, lastY, lastZ = -1;

    private volatile boolean connected;

    private volatile boolean manualDisconnecting = false;

    public Bot(MinecraftProtocol protocol, InetSocketAddress address, ProxyInfo proxy) {
        this.nickname = protocol.getProfile().getName();

        Log.info("Creating Java bot", nickname);

        client = ClientNetworkSessionFactory.factory()
                .setAddress(address.getHostString(), address.getPort())
                .setProtocol(protocol)
                .setProxy(proxy)
                .create();

        client.setFlag(MinecraftConstants.SESSION_SERVICE_KEY, new SessionService());
    }

    @Override
    public void run() {
        if (!Main.isMinimal()) {
            client.addListener(new SessionAdapter() {

                @Override
                public void packetReceived(Session session, Packet packet) {
                    if (packet instanceof ClientboundLoginPacket) {
                        connected = true;
                        Log.info(nickname + " connected");
                        Main.sendJoinMessages(Bot.this);
                    } else if (packet instanceof ClientboundPlayerPositionPacket p) {
                        lastX = p.getPosition().getX();
                        lastY = p.getPosition().getY();
                        lastZ = p.getPosition().getZ();

                        client.send(new ServerboundAcceptTeleportationPacket(p.getId()));
                    } else if (packet instanceof ClientboundPlayerCombatKillPacket) {
                        if (Main.autoRespawnDelay >= 0) {
                            Log.info("Bot " + nickname + " died. Respawning in " + Main.autoRespawnDelay + " ms.");
                            Main.schedule(
                                    () -> client.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN)),
                                    Main.autoRespawnDelay
                            );
                        }
                    } else if (packet instanceof ClientboundResourcePackPushPacket resourcePackPushPacket) {
                        // Pretend the pack was downloaded and loaded
                        client.send(new ServerboundResourcePackPacket(
                                resourcePackPushPacket.getId(),
                                ResourcePackStatus.SUCCESSFULLY_LOADED
                        ));
                    }
                }

                @Override
                public void disconnected(DisconnectedEvent event) {
                    connected = false;
                    Log.info(nickname + " disconnected");

                    // Do not write disconnect reason if disconnected by command
                    if (!manualDisconnecting) {
                        // Fix broken reason string by finding the content with regex
                        Matcher matcher = REASON_CONTENT.matcher(String.valueOf(event.getReason()));

                        StringBuilder reason = new StringBuilder();
                        while (matcher.find()) {
                            reason.append(matcher.group(1));
                        }

                        Log.info(" -> " + reason);

                        if (event.getCause() != null) {
                            event.getCause().printStackTrace();

                            if (event.getCause() instanceof UnexpectedEncryptionException) {
                                Log.warn("Server is running in online (premium) mode. Please use the -o option to use online mode bot.");
                                System.exit(1);
                            }
                        }
                        Log.info();
                    }

                    Main.removeBot(Bot.this);
                }
            });
        }
        client.connect();
    }

    @Override
    public void sendChat(String text) {
        if (text.startsWith("/")) {
            client.send(new ServerboundChatCommandPacket(text.substring(1)));
        } else {
            // Since 1.19 chat packets carry a timestamp, salt and signature. This bot plays offline,
            // so the message is sent unsigned (null signature, salt 0) with no acknowledged messages
            // and a checksum of 0, which the server does not verify.
            client.send(new ServerboundChatPacket(
                    text,
                    Instant.now().toEpochMilli(),
                    0L,
                    null,
                    0,
                    new BitSet(),
                    0
            ));
        }
    }

    @Override
    public String getNickname() {
        return nickname;
    }

    @Override
    public Edition getEdition() {
        return Edition.JAVA;
    }

    @Override
    public boolean canListenToChat() {
        return true;
    }

    @Override
    public void registerMainListener() {
        hasMainListener = true;
        if (Main.isMinimal()) return;
        client.addListener(new MainListener(nickname));
    }

    @Override
    public boolean hasMainListener() {
        return hasMainListener;
    }

    @Override
    public void fallDown() {
        if (connected && lastY > 0) {
            move(0, -0.5, 0);
        }
    }

    public void move(double x, double y, double z) {
        lastX += x;
        lastY += y;
        lastZ += z;
        moveTo(lastX, lastY, lastZ);
    }

    public void moveTo(double x, double y, double z) {
        client.send(new ServerboundMovePlayerPosPacket(true, false, x, y, z));
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public void disconnect() {
        manualDisconnecting = true;
        client.disconnect("Leaving");
    }
}
