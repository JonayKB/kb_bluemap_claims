package es.jonaykb.kb_bluemap_claims;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Reads the Minecraft ↔ Discord links of KB Discord Auth straight from its links.json.
 * No code dependency: if the mod or the file is missing, there are simply no links.
 * Only the public profile fields are kept; the tokens in that file are parsed and dropped, never stored or shown.
 */
final class DiscordLinks {

    record Profile(String discordId, @Nullable String username, @Nullable String globalName, @Nullable String avatarHash) {

        String avatarUrl() {
            if (avatarHash != null && avatarHash.matches("[a-z0-9_]{1,64}")) {
                return "https://cdn.discordapp.com/avatars/" + discordId + "/" + avatarHash + ".png?size=64";
            }
            // Default avatar for accounts without a picture (new username system)
            long index = (Long.parseLong(discordId) >> 22) % 6;
            return "https://cdn.discordapp.com/embed/avatars/" + index + ".png";
        }
    }

    private Map<UUID, Profile> profiles = Map.of();
    private Path loadedFrom;
    private FileTime loadedTime;

    /** Re-reads the file only if it changed. Call from any single thread. */
    Map<UUID, Profile> get() {
        if (!ClaimsConfig.DISCORD_ENABLED.get()) return Map.of();
        Path file = FMLPaths.CONFIGDIR.get().resolve(ClaimsConfig.DISCORD_LINKS_FILE.get()).normalize();
        try {
            if (!Files.isRegularFile(file)) {
                profiles = Map.of();
                loadedFrom = null;
                return profiles;
            }
            FileTime time = Files.getLastModifiedTime(file);
            if (file.equals(loadedFrom) && time.equals(loadedTime)) return profiles;

            Map<UUID, Profile> read = new HashMap<>();
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(r).getAsJsonObject();
                JsonObject links = root.getAsJsonObject("links");
                if (links != null) {
                    for (Map.Entry<String, JsonElement> e : links.entrySet()) {
                        Profile p = parse(e.getKey(), e.getValue());
                        if (p != null) read.put(UUID.fromString(e.getKey()), p);
                    }
                }
            }
            profiles = Map.copyOf(read);
            loadedFrom = file;
            loadedTime = time;
            KbBlueMapClaims.LOGGER.info("[KB BlueMap Claims] {} cuentas de Discord leídas de {}", profiles.size(), file.getFileName());
        } catch (Exception e) {
            // KB Discord Auth writes it atomically, so this should only happen with a hand-edited file.
            KbBlueMapClaims.LOGGER.warn("[KB BlueMap Claims] No se pudo leer {}: {}", file, e.toString());
        }
        return profiles;
    }

    @Nullable
    private static Profile parse(String uuid, JsonElement value) {
        try {
            UUID.fromString(uuid);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (!value.isJsonObject()) return null;
        JsonObject o = value.getAsJsonObject();
        String id = string(o, "discordId");
        if (id == null || !id.matches("\\d{5,25}")) return null;
        return new Profile(id, string(o, "discordUsername"), string(o, "discordGlobalName"), string(o, "discordAvatar"));
    }

    @Nullable
    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
