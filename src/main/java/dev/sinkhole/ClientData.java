package dev.sinkhole;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** The signed "client data" JWT of the Bedrock login: device info and a plain default skin. */
final class ClientData {
    private ClientData() {
    }

    static String payload(AuthService.Identity id, String serverAddress, String gameVersion) {
        JsonObject o = new JsonObject();
        o.add("AnimatedImageData", new JsonArray());
        o.addProperty("CapeData", "");
        o.addProperty("CapeId", "");
        o.addProperty("CapeImageHeight", 0);
        o.addProperty("CapeImageWidth", 0);
        o.addProperty("CapeOnClassicSkin", false);
        o.addProperty("ClientRandomId", new java.util.Random().nextLong() & Long.MAX_VALUE);
        int touchOrMouse = id.deviceOs() == 7 ? 1 : 2; // mouse on Windows, touch on phones
        o.addProperty("CurrentInputMode", touchOrMouse);
        o.addProperty("DefaultInputMode", touchOrMouse);
        o.addProperty("DeviceModel", "");
        o.addProperty("DeviceOS", id.deviceOs());
        // the expected format for this OS is 32 lowercase hex characters
        o.addProperty("DeviceId", UUID.nameUUIDFromBytes(("sinkhole-device-" + id.gamertag()).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", ""));
        o.addProperty("GameVersion", gameVersion);
        o.addProperty("GuiScale", 0);
        o.addProperty("FilterProfanity", false);
        o.addProperty("ClientEditorConnectionIntent", 0);
        o.addProperty("ClientIsEditorCapable", false);
        o.addProperty("LanguageCode", "en_US");
        o.addProperty("PersonaSkin", false);
        o.addProperty("PlatformOfflineId", "");
        o.addProperty("PlatformOnlineId", "");
        o.addProperty("PremiumSkin", false);
        o.addProperty("SelfSignedId", id.uuid().toString());
        o.addProperty("ServerAddress", serverAddress);
        o.addProperty("SkinAnimationData", "");
        o.addProperty("SkinData", Base64.getEncoder().encodeToString(defaultSkin()));
        o.addProperty("SkinGeometryData", Base64.getEncoder().encodeToString(resource("/data/skin_geometry.json")));
        o.addProperty("SkinGeometryDataEngineVersion", Base64.getEncoder().encodeToString("0.0.0".getBytes(StandardCharsets.UTF_8)));
        o.addProperty("SkinId", UUID.randomUUID().toString());
        o.addProperty("PlayFabId", id.uuid().toString().replace("-", "").substring(0, 16));
        o.addProperty("SkinImageHeight", 64);
        o.addProperty("SkinImageWidth", 64);
        o.addProperty("SkinResourcePatch", Base64.getEncoder().encodeToString(resource("/data/skin_resource_patch.json")));
        o.addProperty("SkinColor", "#0");
        o.addProperty("ArmSize", "wide");
        o.add("PersonaPieces", new JsonArray());
        o.add("PieceTintColors", new JsonArray());
        o.addProperty("ThirdPartyName", id.gamertag());
        o.addProperty("UIProfile", 0);
        o.addProperty("TrustedSkin", true);
        o.addProperty("OverrideSkin", false);
        o.addProperty("CompatibleWithClientSideChunkGen", false);
        o.addProperty("MaxViewDistance", 0);
        o.addProperty("MemoryTier", 0);
        o.addProperty("PlatformType", 0);
        o.addProperty("GraphicsMode", 0);
        o.addProperty("PartyId", "");
        o.addProperty("IsPartyLeader", false);
        o.addProperty("ProfileHash", "");
        return o.toString();
    }

    private static byte[] resource(String path) {
        try (java.io.InputStream in = ClientData.class.getResourceAsStream(path)) {
            return in.readAllBytes();
        } catch (java.io.IOException | NullPointerException e) {
            throw new IllegalStateException("Missing resource " + path, e);
        }
    }

    /** A flat-colour 64x64 RGBA skin. */
    private static byte[] defaultSkin() {
        byte[] data = new byte[64 * 64 * 4];
        for (int i = 0; i < data.length; i += 4) {
            data[i] = (byte) 0x8d;
            data[i + 1] = (byte) 0x6a;
            data[i + 2] = (byte) 0x4e;
            data[i + 3] = (byte) 0xff;
        }
        return data;
    }
}
