package me.creepermaxcz.mcbots.bedrock;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.cloudburstmc.protocol.bedrock.codec.BedrockCodec;
import org.cloudburstmc.protocol.bedrock.data.auth.AuthType;
import org.cloudburstmc.protocol.bedrock.data.auth.CertificateChainPayload;
import org.cloudburstmc.protocol.bedrock.packet.LoginPacket;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.lang.JoseException;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Builds an offline (self-signed) Bedrock login, like a client that is not signed in to Xbox Live.
 * <p>
 * Geyser only accepts it with {@code advanced.bedrock.validate-bedrock-login: false}. On NetherNet the chain must be
 * signed with the same key as the transport identity, so the same {@link KeyPair} is used for both.
 */
public final class BedrockLogin {

    private static final Gson GSON = new Gson();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Windows 10/11 in the Bedrock DeviceOS enum */
    public static final int DEVICE_OS_WINDOWS = 7;

    private static final String SKIN_RESOURCE_PATCH = "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}";

    private BedrockLogin() {
    }

    /**
     * @return a random numeric XUID. Floodgate turns the XUID into the player's UUID, so it must be a number.
     */
    public static String randomXuid() {
        // Real XUIDs are 16 digits and start with 2535
        long suffix = Math.floorMod(RANDOM.nextLong(), 1_000_000_000_000L);
        return Long.toString(2_535_000_000_000_000L + suffix);
    }

    public static LoginPacket createLoginPacket(BedrockCodec codec, KeyPair keyPair, String name, String xuid,
                                                String serverAddress) throws JoseException {
        LoginPacket login = new LoginPacket();
        login.setProtocolVersion(codec.getProtocolVersion());
        login.setAuthPayload(new CertificateChainPayload(
                List.of(createChain(keyPair, name, xuid)), AuthType.SELF_SIGNED));
        login.setClientJwt(createClientData(codec, keyPair, name, serverAddress));
        return login;
    }

    private static String encodedPublicKey(KeyPair keyPair) {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    private static String sign(KeyPair keyPair, String payload) throws JoseException {
        JsonWebSignature jws = new JsonWebSignature();
        jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.ECDSA_USING_P384_CURVE_AND_SHA384);
        jws.setHeader("x5u", encodedPublicKey(keyPair));
        jws.setPayload(payload);
        jws.setKey(keyPair.getPrivate());
        return jws.getCompactSerialization();
    }

    private static String createChain(KeyPair keyPair, String name, String xuid) throws JoseException {
        long now = System.currentTimeMillis() / 1000;

        JsonObject extraData = new JsonObject();
        extraData.addProperty("displayName", name);
        extraData.addProperty("identity",
                UUID.nameUUIDFromBytes(("pocket-auth-1-xuid:" + xuid).getBytes(StandardCharsets.UTF_8)).toString());
        extraData.addProperty("XUID", xuid);
        extraData.addProperty("titleId", "896928775"); // Minecraft for Windows

        JsonObject claims = new JsonObject();
        claims.addProperty("nbf", now - 60);
        claims.addProperty("iat", now);
        claims.addProperty("exp", now + 24 * 60 * 60);
        claims.addProperty("identityPublicKey", encodedPublicKey(keyPair));
        claims.add("extraData", extraData);

        return sign(keyPair, GSON.toJson(claims));
    }

    private static String createClientData(BedrockCodec codec, KeyPair keyPair, String name,
                                           String serverAddress) throws JoseException {
        Base64.Encoder base64 = Base64.getEncoder();

        JsonObject data = new JsonObject();
        data.addProperty("GameVersion", codec.getMinecraftVersion());
        data.addProperty("ServerAddress", serverAddress);
        data.addProperty("ThirdPartyName", name);
        data.addProperty("ThirdPartyNameOnly", false);
        data.addProperty("LanguageCode", "en_US");

        data.addProperty("DeviceOS", DEVICE_OS_WINDOWS);
        data.addProperty("DeviceModel", "mc-bots");
        data.addProperty("DeviceId", UUID.randomUUID().toString().replace("-", ""));
        data.addProperty("ClientRandomId", RANDOM.nextLong());
        data.addProperty("SelfSignedId", UUID.randomUUID().toString());
        data.addProperty("PlayFabId", Long.toHexString(RANDOM.nextLong()));
        data.addProperty("PlatformOnlineId", "");
        data.addProperty("PlatformOfflineId", "");

        data.addProperty("CurrentInputMode", 1); // mouse
        data.addProperty("DefaultInputMode", 1);
        data.addProperty("UIProfile", 0); // classic
        data.addProperty("GuiScale", 0);
        data.addProperty("GraphicsMode", 1);
        data.addProperty("MaxViewDistance", 16);
        data.addProperty("MemoryTier", 2);
        data.addProperty("IsEditorMode", false);
        data.addProperty("CompatibleWithClientSideChunkGen", false);

        // A plain 64x64 classic skin
        data.addProperty("SkinId", "Standard_Custom_" + UUID.randomUUID());
        data.addProperty("SkinData", base64.encodeToString(createSkin()));
        data.addProperty("SkinImageWidth", 64);
        data.addProperty("SkinImageHeight", 64);
        data.addProperty("SkinResourcePatch", base64.encodeToString(SKIN_RESOURCE_PATCH.getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinGeometryData", "");
        data.addProperty("SkinGeometryDataEngineVersion", base64.encodeToString("0.0.0".getBytes(StandardCharsets.UTF_8)));
        data.addProperty("SkinAnimationData", "");
        data.addProperty("SkinColor", "#0");
        data.addProperty("ArmSize", "wide");
        data.add("AnimatedImageData", new JsonArray());
        data.add("PersonaPieces", new JsonArray());
        data.add("PieceTintColors", new JsonArray());
        data.addProperty("PersonaSkin", false);
        data.addProperty("PremiumSkin", false);
        data.addProperty("TrustedSkin", false);
        data.addProperty("OverrideSkin", false);
        data.addProperty("CapeId", "");
        data.addProperty("CapeData", "");
        data.addProperty("CapeImageWidth", 0);
        data.addProperty("CapeImageHeight", 0);
        data.addProperty("CapeOnClassicSkin", false);

        return sign(keyPair, GSON.toJson(data));
    }

    /** A random solid colour skin, so bots can be told apart. */
    private static byte[] createSkin() {
        byte[] skin = new byte[64 * 64 * 4];
        byte r = (byte) RANDOM.nextInt(256), g = (byte) RANDOM.nextInt(256), b = (byte) RANDOM.nextInt(256);
        for (int i = 0; i < skin.length; i += 4) {
            skin[i] = r;
            skin[i + 1] = g;
            skin[i + 2] = b;
            skin[i + 3] = (byte) 0xFF;
        }
        return skin;
    }
}
