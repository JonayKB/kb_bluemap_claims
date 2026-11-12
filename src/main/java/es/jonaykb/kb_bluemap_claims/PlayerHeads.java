package es.jonaykb.kb_bluemap_claims;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.properties.Property;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player heads on BlueMap for offline-mode servers. BlueMap asks Mojang for the skin of the player's UUID, which
 * doesn't exist for offline UUIDs, so nobody gets a head. Instead:
 * <ul>
 *   <li>the skin in the player's game profile ("textures"), which is where SkinRestorer (and similar mods) put the
 *       skin set with /skin or fetched for premium names;</li>
 *   <li>if there is none, the skin of the premium account with the same name (Mojang API).</li>
 * </ul>
 * BlueMap only redraws a head when the player joins (at most once an hour), so skin changes while online are
 * detected here and the head is written straight into every map.
 */
final class PlayerHeads {
    private static final long RETRY_MS = TimeUnit.HOURS.toMillis(1);
    private static final String TEXTURE_HOST = "textures.minecraft.net";
    /** SkinRestorer fetches the skin when the player joins; asking Mojang at the same time gets both rate-limited. */
    private static final long PREMIUM_LOOKUP_DELAY_MS = 15_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KB BlueMap Claims heads");
        t.setDaemon(true);
        return t;
    });

    /** Skin URL from the game profile of each online player. Written on the server thread. */
    private final Map<UUID, String> profileSkins = new ConcurrentHashMap<>();
    private final Map<UUID, String> names = new ConcurrentHashMap<>();
    /** When each online player was first seen, to give SkinRestorer time to set the skin before asking Mojang. */
    private final Map<UUID, Long> seenAt = new ConcurrentHashMap<>();
    /** Skin URL last written as a head, per player. */
    private final Map<UUID, String> written = new ConcurrentHashMap<>();
    /** Premium lookups by name: result (or "" for none) and when. */
    private final Map<String, String> premiumUrls = new ConcurrentHashMap<>();
    private final Map<String, Long> premiumTried = new ConcurrentHashMap<>();
    private int ticks;

    /**
     * Changes when the player's head on the map changes, so the web can ask for the new image (BlueMap serves heads
     * with a one-day cache). Null until a head has been written.
     */
    @Nullable
    String version(UUID id) {
        String url = written.get(id);
        return url == null || url.isEmpty() ? null : Integer.toHexString(url.hashCode());
    }

    /** On every BlueMap (re)load: make BlueMap's own join-time update use these skins too. */
    void install(BlueMapAPI api) {
        if (!ClaimsConfig.SKINS_ENABLED.get()) return;
        api.getPlugin().setSkinProvider(this::loadForBlueMap);
        written.clear(); // maps may have been reset: write again
    }

    void stop() {
        profileSkins.clear();
        names.clear();
        seenAt.clear();
        written.clear();
    }

    /** Server tick. Every 2 s: look for new or changed skins of online players. */
    void tick(MinecraftServer server) {
        if (++ticks < 40 || !ClaimsConfig.SKINS_ENABLED.get()) return;
        ticks = 0;
        BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
        if (api == null) return;
        long now = System.currentTimeMillis();
        seenAt.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID id = p.getUUID();
            names.put(id, p.getGameProfile().getName());
            long seen = seenAt.computeIfAbsent(id, k -> now);
            String url = skinUrl(p);
            if (url != null) profileSkins.put(id, url);
            else profileSkins.remove(id);
            if (url == null && now - seen < PREMIUM_LOOKUP_DELAY_MS) continue;
            String last = written.get(id);
            if (url != null ? !url.equals(last) : last == null) {
                written.put(id, url != null ? url : "");
                String name = p.getGameProfile().getName();
                worker.execute(() -> writeHead(api, id, name));
            }
        }
    }

    // ───────────────────────────── skins ─────────────────────────────

    private Optional<BufferedImage> loadForBlueMap(UUID id) throws IOException {
        return Optional.ofNullable(skin(id, names.get(id)));
    }

    /** Worker thread. */
    @Nullable
    private BufferedImage skin(UUID id, @Nullable String name) throws IOException {
        String url = profileSkins.get(id);
        if (url == null && name != null && ClaimsConfig.SKINS_PREMIUM_BY_NAME.get()) url = premiumSkinUrl(name);
        return url == null ? null : download(url);
    }

    private void writeHead(BlueMapAPI api, UUID id, String name) {
        try {
            BufferedImage skin = skin(id, name);
            if (skin == null) return; // BlueMap keeps showing Steve
            BufferedImage head = api.getPlugin().getPlayerMarkerIconFactory().apply(id, likeMinecraft(skin));
            for (BlueMapMap map : api.getMaps()) {
                try (OutputStream out = map.getAssetStorage().writeAsset("playerheads/" + id + ".png")) {
                    ImageIO.write(head, "png", out);
                }
            }
        } catch (Exception e) {
            written.remove(id); // try again later
            KbBlueMapClaims.LOGGER.debug("[KB BlueMap Claims] No se pudo actualizar la cabeza de {}: {}", name, e.toString());
        }
    }

    /**
     * Same clean-up Minecraft does before drawing a skin, for the head: the base head is always opaque, and on old
     * 64x32 skins a hat layer with no transparent pixel at all counts as no hat (Notch's skin has a solid black one,
     * which BlueMap would draw as a black square).
     */
    static BufferedImage likeMinecraft(BufferedImage skin) {
        BufferedImage out = new BufferedImage(skin.getWidth(), skin.getHeight(), BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(skin, 0, 0, null);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 32; x++) out.setRGB(x, y, out.getRGB(x, y) | 0xFF000000);
        }
        if (skin.getHeight() == 32) {
            boolean anyTransparent = false;
            for (int y = 0; y < 32 && !anyTransparent; y++) {
                for (int x = 32; x < 64; x++) {
                    if ((out.getRGB(x, y) >>> 24) < 128) {
                        anyTransparent = true;
                        break;
                    }
                }
            }
            if (!anyTransparent) {
                for (int y = 0; y < 32; y++) {
                    for (int x = 32; x < 64; x++) out.setRGB(x, y, 0);
                }
            }
        }
        return out;
    }

    /** Skin URL in the player's "textures" property (set by SkinRestorer, or by Mojang on premium servers). */
    @Nullable
    private static String skinUrl(ServerPlayer p) {
        for (Property prop : p.getGameProfile().getProperties().get("textures")) {
            String url = urlFromTextures(prop.getValue());
            if (url != null) return url;
        }
        return null;
    }

    @Nullable
    private static String urlFromTextures(String base64) {
        try {
            JsonObject json = JsonParser.parseString(new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject skin = json.getAsJsonObject("textures").getAsJsonObject("SKIN");
            return skin == null ? null : skin.get("url").getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Skin of the premium account with this name, cached for an hour (also when there is none). */
    @Nullable
    private String premiumSkinUrl(String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) return null;
        String key = name.toLowerCase();
        Long tried = premiumTried.get(key);
        if (tried != null && System.currentTimeMillis() - tried < RETRY_MS) {
            String cached = premiumUrls.get(key);
            return cached == null || cached.isEmpty() ? null : cached;
        }
        premiumTried.put(key, System.currentTimeMillis());
        String url = null;
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                String premiumId = JsonParser.parseString(res.body()).getAsJsonObject().get("id").getAsString();
                HttpResponse<String> prof = http.send(HttpRequest.newBuilder(
                                URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + premiumId))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                if (prof.statusCode() == 200) {
                    for (JsonElement e : JsonParser.parseString(prof.body()).getAsJsonObject().getAsJsonArray("properties")) {
                        JsonObject o = e.getAsJsonObject();
                        if ("textures".equals(o.get("name").getAsString())) url = urlFromTextures(o.get("value").getAsString());
                    }
                }
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            premiumTried.remove(key); // network error: try again next time
            return null;
        }
        premiumUrls.put(key, url == null ? "" : url);
        return url;
    }

    /** Only Mojang's texture server: the URL comes from player data. */
    private BufferedImage download(String url) throws IOException {
        URI uri = URI.create(url);
        if (!TEXTURE_HOST.equalsIgnoreCase(uri.getHost()) || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
            throw new IOException("Skin URL not from " + TEXTURE_HOST + ": " + url);
        }
        try {
            HttpResponse<byte[]> res = http.send(HttpRequest.newBuilder(URI.create("https://" + TEXTURE_HOST + uri.getRawPath()))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode() + " for " + url);
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(res.body()));
            if (img == null || img.getWidth() != 64 || (img.getHeight() != 64 && img.getHeight() != 32)) {
                throw new IOException("Not a skin: " + url);
            }
            return img;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
