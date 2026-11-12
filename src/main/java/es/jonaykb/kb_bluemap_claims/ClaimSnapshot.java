package es.jonaykb.kb_bluemap_claims;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.claims.player.api.IPlayerDimensionClaimsAPI;
import xaero.pac.common.parties.party.member.api.IPartyMemberAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.player.api.IServerPlayerClaimInfoAPI;
import xaero.pac.common.server.parties.party.api.IServerPartyAPI;
import xaero.pac.common.server.player.config.api.IPlayerConfigAPI;
import xaero.pac.common.server.player.config.api.PlayerConfigOptions;
import xaero.pac.common.server.player.config.api.PlayerConfigType;

/**
 * Immutable copy of every claim, taken on the server thread so the slow part (shapes, HTML)
 * can run on another thread without touching Open Parties and Claims.
 * <p>
 * Uses the deprecated v1 player config API on purpose: it also works on older OPaC releases,
 * while the v2 one only exists in recent versions.
 */
@SuppressWarnings("deprecation")
record ClaimSnapshot(List<Group> groups) {

    enum Kind { PLAYER, SERVER, EXPIRED }

    record Member(UUID id, String name, boolean owner) {}

    record Party(String name, List<Member> members) {}

    record Owner(UUID id, String name, Kind kind, @Nullable Party party, int claimCount, int forceloadCount) {}

    /** Chunks of one owner, in one dimension, with one name and colour (OPaC sub-claim). */
    record Group(Owner owner, ResourceLocation dimension, ServerLevel level, int subConfig,
                 String name, int color, Set<ChunkPos> chunks) {}

    static ClaimSnapshot capture(MinecraftServer server) {
        OpenPACServerAPI api = OpenPACServerAPI.get(server);
        boolean showServer = ClaimsConfig.SHOW_SERVER_CLAIMS.get();
        boolean showExpired = ClaimsConfig.SHOW_EXPIRED_CLAIMS.get();

        List<Group> groups = new ArrayList<>();
        api.getServerClaimsManager().getPlayerInfoStream().forEach(info -> {
            Kind kind = kindOf(api, info.getPlayerId());
            if (kind == Kind.SERVER && !showServer) return;
            if (kind == Kind.EXPIRED && !showExpired) return;

            Owner owner = new Owner(info.getPlayerId(), cleanName(info.getPlayerUsername()), kind,
                    kind == Kind.PLAYER ? partyOf(api, info.getPlayerId()) : null,
                    info.getClaimCount(), info.getForceloadCount());

            info.getStream().forEach(dimEntry -> {
                ResourceLocation dim = dimEntry.getKey();
                ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
                if (level == null) return;
                collectGroups(info, owner, dim, level, dimEntry.getValue(), groups);
            });
        });
        return new ClaimSnapshot(List.copyOf(groups));
    }

    private static void collectGroups(IServerPlayerClaimInfoAPI info, Owner owner, ResourceLocation dim,
                                      ServerLevel level, IPlayerDimensionClaimsAPI claims, List<Group> out) {
        Map<Integer, Set<ChunkPos>> bySub = new HashMap<>();
        claims.getStream().forEach((IPlayerClaimPosListAPI list) -> {
            int sub = list.getClaimState().getSubConfigIndex();
            Set<ChunkPos> set = bySub.computeIfAbsent(sub, k -> new HashSet<>());
            list.getStream().forEach(set::add);
        });
        bySub.forEach((sub, chunks) -> {
            if (chunks.isEmpty()) return;
            out.add(new Group(owner, dim, level, sub, claimName(info, owner, sub), claimColor(info, sub), Set.copyOf(chunks)));
        });
    }

    private static String claimName(IServerPlayerClaimInfoAPI info, Owner owner, int sub) {
        String name = sub < 0 ? null : info.getClaimsName(sub);
        if (isBlank(name)) name = info.getClaimsName();
        if (!isBlank(name)) return name;
        return switch (owner.kind()) {
            case SERVER -> "Zona del servidor";
            case EXPIRED -> "Claim caducado";
            case PLAYER -> "Claim de " + owner.name();
        };
    }

    private static int claimColor(IServerPlayerClaimInfoAPI info, int sub) {
        Integer color = sub < 0 ? null : info.getClaimsColor(sub);
        return (color != null ? color : info.getClaimsColor()) & 0xFFFFFF;
    }

    private static Kind kindOf(OpenPACServerAPI api, UUID id) {
        try {
            IPlayerConfigAPI config = api.getPlayerConfigs().getLoadedConfig(id);
            PlayerConfigType type = config == null ? null : config.getType();
            if (type == PlayerConfigType.SERVER) return Kind.SERVER;
            if (type == PlayerConfigType.EXPIRED) return Kind.EXPIRED;
        } catch (RuntimeException ignored) {
            // Treat as a normal player
        }
        return Kind.PLAYER;
    }

    @Nullable
    static Party partyOf(OpenPACServerAPI api, UUID playerId) {
        IServerPartyAPI party = api.getPartyManager().getPartyByMember(playerId);
        if (party == null) return null;

        String name = null;
        try {
            IPlayerConfigAPI ownerConfig = api.getPlayerConfigs().getLoadedConfig(party.getOwner().getUUID());
            if (ownerConfig != null) name = ownerConfig.getEffective(PlayerConfigOptions.PARTY_NAME);
        } catch (RuntimeException ignored) {
            // Fall back to the default name
        }
        if (isBlank(name)) name = party.getDefaultName();

        List<Member> members = new ArrayList<>();
        party.getMemberInfoStream().forEach((IPartyMemberAPI m) ->
                members.add(new Member(m.getUUID(), m.getUsername(), m.isOwner())));
        members.sort((a, b) -> a.owner() != b.owner() ? (a.owner() ? -1 : 1) : a.name().compareToIgnoreCase(b.name()));
        return new Party(name, List.copyOf(members));
    }

    /** OPaC quotes special owners, e.g. "\"Server\"". */
    private static String cleanName(@Nullable String name) {
        if (name == null) return "?";
        if (name.length() > 2 && name.startsWith("\"") && name.endsWith("\"")) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }

    private static boolean isBlank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
