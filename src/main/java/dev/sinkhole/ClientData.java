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
        o.addProperty("ArmSize", "wide");
        o.addProperty("CapeData", "");
        o.addProperty("CapeId", "");
        o.addProperty("CapeImageHeight", 0);
        o.addProperty("CapeImageWidth", 0);
        o.addProperty("CapeOnClassicSkin", false);
        o.addProperty("ClientRandomId", System.nanoTime());
        o.addProperty("CompatibleWithClientSideChunkGen", false);
        o.addProperty("CurrentInputMode", 1);
        o.addProperty("DefaultInputMode", 1);
        o.addProperty("DeviceId", UUID.nameUUIDFromBytes(("sinkhole-device-" + id.gamertag()).getBytes(StandardCharsets.UTF_8)).toString());
        o.addProperty("DeviceModel", "Sinkhole");
        o.addProperty("DeviceOS", 7); // Windows 10/11
        o.addProperty("GameVersion", gameVersion);
        o.addProperty("GuiScale", 0);
        o.addProperty("IsEditorMode", false);
        o.addProperty("LanguageCode", "en_US");
        o.addProperty("OverrideSkin", false);
        o.add("PersonaPieces", new JsonArray());
        o.addProperty("PersonaSkin", false);
        o.add("PieceTintColors", new JsonArray());
        o.addProperty("PlatformOfflineId", "");
        o.addProperty("PlatformOnlineId", "");
        o.addProperty("PlayFabId", id.uuid().toString().replace("-", "").substring(0, 16));
        o.addProperty("PremiumSkin", false);
        o.addProperty("SelfSignedId", id.uuid().toString());
        o.addProperty("ServerAddress", serverAddress);
        o.addProperty("SkinAnimationData", "");
        o.addProperty("SkinColor", "#0");
        o.addProperty("SkinData", Base64.getEncoder().encodeToString(defaultSkin()));
        o.addProperty("SkinGeometryData", Base64.getEncoder().encodeToString("null".getBytes(StandardCharsets.UTF_8)));
        o.addProperty("SkinGeometryDataEngineVersion", Base64.getEncoder().encodeToString("1.14.60".getBytes(StandardCharsets.UTF_8)));
        o.addProperty("SkinId", UUID.randomUUID() + ".Custom");
        o.addProperty("SkinImageHeight", 64);
        o.addProperty("SkinImageWidth", 64);
        o.addProperty("SkinResourcePatch", Base64.getEncoder().encodeToString(
                "{\"geometry\":{\"default\":\"geometry.humanoid.custom\"}}".getBytes(StandardCharsets.UTF_8)));
        o.addProperty("ThirdPartyName", id.gamertag());
        o.addProperty("ThirdPartyNameOnly", false);
        o.addProperty("TrustedSkin", true);
        o.addProperty("UIProfile", 0);
        return o.toString();
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
