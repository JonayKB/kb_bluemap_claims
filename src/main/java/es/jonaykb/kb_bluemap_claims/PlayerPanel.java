package es.jonaykb.kb_bluemap_claims;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

import javax.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Member;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Party;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.player.config.api.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.PlayerConfigOptions;

/**
 * Panel on the BlueMap web for players logged in with Discord (KB Discord Auth): their health and their OPaC
 * party's, centre on / follow them, and their last death. KB Discord Auth does the login and calls
 * {@link #handle} for {@code /web/api/map} with the player's linked accounts; this class only answers for those.
 * The web part (panel.js / panel.css) is copied into BlueMap's webroot and registered on each BlueMap start.
 */
final class PlayerPanel {
    static final String AUTH_MOD = "kb_discord_auth";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String WEB_DIR = "assets/kb_bluemap_claims";

    record Death(String dimension, double x, double y, double z, long time) {}

    private final Path deathsFile = FMLPaths.CONFIGDIR.get().resolve(KbBlueMapClaims.MODID).resolve("deaths.json");
    /** Server thread only. */
    private Map<String, Death> deaths;
    private volatile MinecraftServer server;
    private final PlayerHeads heads;

    PlayerPanel(PlayerHeads heads) {
        this.heads = heads;
    }

    // ───────────────────────────── setup ─────────────────────────────

    /** Mod bus, InterModEnqueueEvent. */
    void sendImc() {
        if (!ModList.get().isLoaded(AUTH_MOD)) return;
        BiFunction<Map<UUID, String>, Map<String, String>, String> handler = this::handle;
        InterModComms.sendTo(AUTH_MOD, "web_api", () -> Map.entry("map", handler));
    }

    void onServerStarted(MinecraftServer server) {
        this.server = server;
        deaths = loadDeaths();
    }

    void onServerStopping() {
        server = null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || deaths == null) return;
        deaths.put(p.getStringUUID(), new Death(p.level().dimension().location().toString(),
                p.getX(), p.getY(), p.getZ(), System.currentTimeMillis()));
        saveDeaths();
    }

    /** Copies the web files into BlueMap's webroot and registers them. BlueMap forgets them on every reload. */
    static void installWeb(BlueMapAPI api) {
        if (!ClaimsConfig.PANEL_ENABLED.get() || !ModList.get().isLoaded(AUTH_MOD)) return;
        String authUrl = ClaimsConfig.AUTH_URL.get().replaceAll("/+$", "");
        if (authUrl.isBlank()) {
            KbBlueMapClaims.LOGGER.warn("[KB BlueMap Claims] Panel de jugador desactivado: falta web.authUrl en la config");
            return;
        }
        try {
            Path dir = api.getWebApp().getWebRoot().resolve(WEB_DIR);
            Files.createDirectories(dir);
            JsonObject cfg = new JsonObject();
            cfg.addProperty("authUrl", authUrl);
            cfg.addProperty("pollSeconds", ClaimsConfig.POLL_SECONDS.get());
            cfg.addProperty("deathMarker", ClaimsConfig.DEATH_MARKER.get());
            String js = "window.KB_MAP_PANEL = " + cfg + ";\n" + resource("panel.js");
            String css = resource("panel.css");
            Files.writeString(dir.resolve("panel.js"), js, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("panel.css"), css, StandardCharsets.UTF_8);
            // Version in the URL so browsers and nginx don't keep an old copy after an update
            api.getWebApp().registerStyle(WEB_DIR + "/panel.css?v=" + hash(css));
            api.getWebApp().registerScript(WEB_DIR + "/panel.js?v=" + hash(js));
            KbBlueMapClaims.LOGGER.info("[KB BlueMap Claims] Panel de jugador instalado en la web de BlueMap ({})", authUrl);
        } catch (IOException e) {
            KbBlueMapClaims.LOGGER.error("[KB BlueMap Claims] No se pudo instalar el panel en la web de BlueMap", e);
        }
    }

    // ───────────────────────────── /web/api/map ─────────────────────────────

    /** HTTP thread. {@code accounts}: the Minecraft accounts (UUID → name) of the logged-in Discord user. */
    @Nullable
    String handle(Map<UUID, String> accounts, Map<String, String> query) {
        MinecraftServer s = server;
        if (s == null || !ClaimsConfig.PANEL_ENABLED.get()) return null;
        CompletableFuture<String> f = CompletableFuture.supplyAsync(() -> build(s, accounts, query.get("account")), s);
        try {
            return f.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Server did not answer", e);
        }
    }

    /** Server thread. */
    private String build(MinecraftServer s, Map<UUID, String> accounts, @Nullable String wanted) {
        BlueMapAPI blueMap = BlueMapAPI.getInstance().orElse(null);
        JsonObject out = new JsonObject();

        // Which account: the one asked for, else the first online one, else the first
        UUID selected = null;
        for (UUID a : accounts.keySet()) {
            if (a.toString().equals(wanted)) selected = a;
        }
        if (selected == null) {
            for (UUID a : accounts.keySet()) {
                if (selected == null && s.getPlayerList().getPlayer(a) != null) selected = a;
            }
        }
        if (selected == null && !accounts.isEmpty()) selected = accounts.keySet().iterator().next();

        JsonArray list = new JsonArray();
        for (Map.Entry<UUID, String> a : accounts.entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("uuid", a.getKey().toString());
            o.addProperty("name", nameOf(s, a.getKey(), a.getValue()));
            o.addProperty("online", s.getPlayerList().getPlayer(a.getKey()) != null);
            list.add(o);
        }
        out.add("accounts", list);
        if (selected == null) return out.toString();

        JsonObject me = status(s, blueMap, selected, accounts.get(selected), true);
        if (ClaimsConfig.DEATH_MARKER.get()) {
            JsonObject death = death(s, blueMap, selected);
            if (death != null) me.add("death", death);
        }
        out.add("player", me);

        OpenPACServerAPI opac = OpenPACServerAPI.get(s);
        Party party = ClaimSnapshot.partyOf(opac, selected);
        if (party != null) {
            JsonObject p = new JsonObject();
            p.addProperty("name", party.name());
            JsonArray members = new JsonArray();
            for (Member m : party.members()) {
                if (m.id().equals(selected)) continue;
                JsonObject o = status(s, blueMap, m.id(), m.name(), sharesLocation(opac, m.id()));
                o.addProperty("owner", m.owner());
                members.add(o);
            }
            p.add("members", members);
            out.add("party", p);
        }
        return out.toString();
    }

    /** OPaC's own "share my location with my party" option (on by default). */
    private static boolean sharesLocation(OpenPACServerAPI opac, UUID id) {
        try {
            IPlayerConfigAPI config = opac.getPlayerConfigs().getLoadedConfig(id);
            return config == null || !Boolean.FALSE.equals(config.getEffective(PlayerConfigOptions.SHARE_LOCATION_WITH_PARTY));
        } catch (RuntimeException e) {
            return false; // when in doubt, don't share
        }
    }

    private JsonObject status(MinecraftServer s, @Nullable BlueMapAPI blueMap, UUID id, @Nullable String knownName,
                                     boolean withPlace) {
        JsonObject o = new JsonObject();
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        o.addProperty("uuid", id.toString());
        o.addProperty("name", nameOf(s, id, knownName));
        o.addProperty("online", p != null);
        String head = heads.version(id);
        if (head != null) o.addProperty("head", head);
        if (p == null) return o;
        o.addProperty("health", round(p.getHealth()));
        o.addProperty("maxHealth", round(p.getMaxHealth()));
        o.addProperty("absorption", round(p.getAbsorptionAmount()));
        o.addProperty("dead", p.isDeadOrDying());
        o.addProperty("spectator", p.isSpectator());
        if (withPlace) addPlace(o, blueMap, p.serverLevel(), p.getX(), p.getY(), p.getZ());
        else o.addProperty("hiddenLocation", true);
        return o;
    }

    @Nullable
    private JsonObject death(MinecraftServer s, @Nullable BlueMapAPI blueMap, UUID id) {
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        Death stored = deaths == null ? null : deaths.get(id.toString());
        // Online: vanilla's own record (also has deaths from before this mod). Offline: ours.
        Optional<GlobalPos> vanilla = p == null ? Optional.empty() : p.getLastDeathLocation();
        String dim;
        double x, y, z;
        if (vanilla.isPresent()) {
            GlobalPos g = vanilla.get();
            dim = g.dimension().location().toString();
            x = g.pos().getX() + 0.5;
            y = g.pos().getY();
            z = g.pos().getZ() + 0.5;
        } else if (stored != null) {
            dim = stored.dimension();
            x = stored.x();
            y = stored.y();
            z = stored.z();
        } else {
            return null;
        }
        ServerLevel level = levelOf(s, dim);
        if (level == null) return null;
        JsonObject o = new JsonObject();
        addPlace(o, blueMap, level, x, y, z);
        if (stored != null && stored.dimension().equals(dim)
                && Math.abs(stored.x() - x) < 1.5 && Math.abs(stored.z() - z) < 1.5) {
            o.addProperty("time", stored.time());
        }
        return o;
    }

    private static void addPlace(JsonObject o, @Nullable BlueMapAPI blueMap, ServerLevel level, double x, double y, double z) {
        o.addProperty("dimension", level.dimension().location().toString());
        o.addProperty("x", round(x));
        o.addProperty("y", round(y));
        o.addProperty("z", round(z));
        JsonArray maps = new JsonArray();
        if (blueMap != null) {
            blueMap.getWorld(level).ifPresent(w -> {
                for (BlueMapMap m : w.getMaps()) maps.add(m.getId());
            });
        }
        o.add("maps", maps);
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer s, String dimension) {
        for (ServerLevel l : s.getAllLevels()) {
            if (l.dimension().location().toString().equals(dimension)) return l;
        }
        return null;
    }

    private static String nameOf(MinecraftServer s, UUID id, @Nullable String known) {
        ServerPlayer p = s.getPlayerList().getPlayer(id);
        if (p != null) return p.getGameProfile().getName();
        if (known != null) return known;
        Optional<GameProfile> cached = s.getProfileCache() == null ? Optional.empty() : s.getProfileCache().get(id);
        return cached.map(GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ───────────────────────────── storage ─────────────────────────────

    private Map<String, Death> loadDeaths() {
        if (Files.exists(deathsFile)) {
            try (Reader r = Files.newBufferedReader(deathsFile, StandardCharsets.UTF_8)) {
                Map<String, Death> read = GSON.fromJson(r, new TypeToken<LinkedHashMap<String, Death>>() {}.getType());
                if (read != null) return new LinkedHashMap<>(read);
            } catch (Exception e) {
                KbBlueMapClaims.LOGGER.warn("[KB BlueMap Claims] No se pudo leer {}: {}", deathsFile, e.toString());
            }
        }
        return new LinkedHashMap<>();
    }

    private void saveDeaths() {
        try {
            Files.createDirectories(deathsFile.getParent());
            Path tmp = deathsFile.resolveSibling(deathsFile.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(deaths, w);
            }
            try {
                Files.move(tmp, deathsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, deathsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            KbBlueMapClaims.LOGGER.error("[KB BlueMap Claims] No se pudo guardar {}", deathsFile, e);
        }
    }

    private static String resource(String name) throws IOException {
        try (InputStream in = PlayerPanel.class.getResourceAsStream("/web/" + name)) {
            if (in == null) throw new IOException("Missing /web/" + name + " in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 4);
        } catch (Exception e) {
            return "0";
        }
    }
}
