package es.jonaykb.kb_bluemap_claims;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import de.bluecolored.bluemap.api.BlueMapAPI;
import es.jonaykb.kb_bluemap_claims.DiscordLinks.Profile;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.network.NetworkConstants;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;

@Mod(KbBlueMapClaims.MODID)
public class KbBlueMapClaims {

    public static final String MODID = "kb_bluemap_claims";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** At most this long between the first of a burst of chunk changes and the refresh. */
    private static final long MAX_CHANGE_WAIT_MS = 30_000;

    private static KbBlueMapClaims instance;

    private final DiscordLinks discordLinks = new DiscordLinks();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "KB BlueMap Claims");
        t.setDaemon(true);
        return t;
    });
    private final Consumer<BlueMapAPI> onBlueMapEnable = api -> requestRefresh();
    private final Consumer<BlueMapAPI> onBlueMapDisable = api -> LOGGER.info("[KB BlueMap Claims] BlueMap desactivado");

    private MinecraftServer server;
    private boolean trackerRegistered;

    // Written from OPaC/BlueMap/command threads, read on the server thread
    private volatile boolean fullRequested;
    private volatile long firstChangeAt;
    private volatile long lastChangeAt;
    private long nextFullAt;
    private volatile boolean running;
    private int tickCounter;

    // Status for /kbclaims status
    private volatile long lastRefreshAt;
    private volatile long lastRefreshMs;
    private volatile ClaimMarkers.Result lastResult;
    private volatile String lastError;

    public KbBlueMapClaims() {
        instance = this;
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ClaimsConfig.SPEC);
        // Server-only: clients can join without it
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> NetworkConstants.IGNORESERVERONLY, (remote, isServer) -> true));
        MinecraftForge.EVENT_BUS.register(this);
    }

    static KbBlueMapClaims get() {
        return instance;
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        server = event.getServer();
        if (!trackerRegistered) {
            OpenPACServerAPI.get(server).getServerClaimsManager().getTracker().register(new IClaimsManagerListenerAPI() {
                @Override
                public void onWholeRegionChange(ResourceLocation dimension, int regionX, int regionZ) {
                    markChanged();
                }

                @Override
                public void onChunkChange(ResourceLocation dimension, int chunkX, int chunkZ, IPlayerChunkClaimAPI claim) {
                    markChanged();
                }

                @Override
                public void onDimensionChange(ResourceLocation dimension) {
                    markChanged();
                }
            });
            trackerRegistered = true;
        }
        BlueMapAPI.onEnable(onBlueMapEnable);
        BlueMapAPI.onDisable(onBlueMapDisable);
        requestRefresh();
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        BlueMapAPI.unregisterListener(onBlueMapEnable);
        BlueMapAPI.unregisterListener(onBlueMapDisable);
        server = null;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        ClaimsCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) return;
        if (++tickCounter < 20) return; // once a second is plenty
        tickCounter = 0;
        if (running) return;

        long now = System.currentTimeMillis();
        boolean due = fullRequested;
        long first = firstChangeAt;
        if (!due && first != 0) {
            long delay = ClaimsConfig.CHANGE_DELAY_SECONDS.get() * 1000L;
            due = now - lastChangeAt >= delay || now - first >= MAX_CHANGE_WAIT_MS;
        }
        int fullMinutes = ClaimsConfig.FULL_REFRESH_MINUTES.get();
        if (!due && fullMinutes > 0 && now >= nextFullAt) due = true;
        if (!due) return;

        BlueMapAPI blueMap = BlueMapAPI.getInstance().orElse(null);
        if (blueMap == null) return; // BlueMap still loading: onEnable will ask again

        fullRequested = false;
        firstChangeAt = 0;
        nextFullAt = fullMinutes > 0 ? now + fullMinutes * 60_000L : Long.MAX_VALUE;
        refresh(blueMap);
    }

    /** Takes the snapshot here (server thread) and does the rest on the worker thread. */
    private void refresh(BlueMapAPI blueMap) {
        ClaimSnapshot snapshot;
        try {
            snapshot = ClaimSnapshot.capture(server);
        } catch (RuntimeException e) {
            lastError = e.toString();
            LOGGER.error("[KB BlueMap Claims] No se pudieron leer los claims", e);
            return;
        }
        running = true;
        long start = System.currentTimeMillis();
        worker.execute(() -> {
            try {
                Map<UUID, Profile> discord = discordLinks.get();
                ClaimMarkers.Result result = ClaimMarkers.publish(blueMap, snapshot, discord);
                lastResult = result;
                lastError = null;
                lastRefreshAt = System.currentTimeMillis();
                lastRefreshMs = lastRefreshAt - start;
                LOGGER.debug("[KB BlueMap Claims] {} zonas de {} dueños en {} ms", result.zones(), result.owners(), lastRefreshMs);
            } catch (Exception e) {
                lastError = e.toString();
                LOGGER.error("[KB BlueMap Claims] Error al actualizar BlueMap", e);
            } finally {
                running = false;
            }
        });
    }

    private void markChanged() {
        long now = System.currentTimeMillis();
        if (firstChangeAt == 0) firstChangeAt = now;
        lastChangeAt = now;
    }

    void requestRefresh() {
        fullRequested = true;
    }

    String status() {
        StringBuilder s = new StringBuilder();
        s.append("BlueMap: ").append(BlueMapAPI.getInstance().isPresent() ? "activo" : "no cargado");
        ClaimMarkers.Result r = lastResult;
        if (r == null) {
            s.append("\nAún no se ha actualizado el mapa.");
        } else {
            long ago = (System.currentTimeMillis() - lastRefreshAt) / 1000;
            s.append("\nÚltima actualización: hace ").append(ago).append(" s (").append(lastRefreshMs).append(" ms)");
            s.append("\nZonas: ").append(r.zones()).append(" · dueños: ").append(r.owners())
                    .append(" · con Discord: ").append(r.discordLinked());
        }
        if (running) s.append("\nActualizando ahora…");
        if (lastError != null) s.append("\nÚltimo error: ").append(lastError);
        return s.toString();
    }
}
