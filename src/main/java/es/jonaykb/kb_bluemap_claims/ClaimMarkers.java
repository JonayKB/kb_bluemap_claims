package es.jonaykb.kb_bluemap_claims;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Group;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Member;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Owner;
import es.jonaykb.kb_bluemap_claims.ClaimSnapshot.Party;
import es.jonaykb.kb_bluemap_claims.DiscordLinks.Profile;
import es.jonaykb.kb_bluemap_claims.shape.ClaimShapes;

/** Builds the BlueMap markers from a snapshot and swaps them into every map. Runs off the server thread. */
final class ClaimMarkers {

    static final String MARKER_SET_ID = "kb_bluemap_claims";

    record Result(int zones, int owners, int discordLinked) {}

    private ClaimMarkers() {}

    static Result publish(BlueMapAPI blueMap, ClaimSnapshot snapshot, Map<UUID, Profile> discord) {
        float minY = ClaimsConfig.MIN_Y.get().floatValue();
        float maxY = ClaimsConfig.MAX_Y.get().floatValue();
        boolean flat = Math.abs(maxY - minY) < 0.01f;
        boolean depthTest = ClaimsConfig.DEPTH_TEST.get();
        float opacity = ClaimsConfig.FILL_OPACITY.get().floatValue();
        int lineWidth = ClaimsConfig.LINE_WIDTH.get();

        // BlueMap world id -> markers for that world
        Map<String, Map<String, Marker>> byWorld = new HashMap<>();
        Map<UUID, Boolean> owners = new HashMap<>();
        int zones = 0;

        for (Group group : snapshot.groups()) {
            Optional<BlueMapWorld> world = blueMap.getWorld(group.level());
            if (world.isEmpty()) continue;
            Map<String, Marker> markers = byWorld.computeIfAbsent(world.get().getId(), k -> new HashMap<>());

            List<ClaimShapes.Area> areas = ClaimShapes.areas(group.chunks());
            String detail = detailHtml(group, discord);
            Color fill = new Color(group.color(), opacity);
            Color line = new Color(group.color(), 1f);
            String baseId = "claim_" + group.owner().id() + "_" + group.dimension().toString().replace(':', '_')
                    + "_" + group.subConfig();

            for (int i = 0; i < areas.size(); i++) {
                ClaimShapes.Area area = areas.get(i);
                Marker marker = flat
                        ? ShapeMarker.builder()
                            .label(group.name())
                            .shape(area.outline(), minY)
                            .holes(area.holes())
                            .centerPosition()
                            .fillColor(fill).lineColor(line).lineWidth(lineWidth)
                            .depthTestEnabled(depthTest)
                            .detail(detail)
                            .build()
                        : ExtrudeMarker.builder()
                            .label(group.name())
                            .shape(area.outline(), minY, maxY)
                            .holes(area.holes())
                            .centerPosition()
                            .fillColor(fill).lineColor(line).lineWidth(lineWidth)
                            .depthTestEnabled(depthTest)
                            .detail(detail)
                            .build();
                markers.put(baseId + "_" + i, marker);
                zones++;
            }
            owners.put(group.owner().id(), discord.containsKey(group.owner().id()));
        }

        String label = ClaimsConfig.MARKER_SET_LABEL.get();
        boolean hidden = ClaimsConfig.DEFAULT_HIDDEN.get();
        for (BlueMapMap map : blueMap.getMaps()) {
            MarkerSet set = map.getMarkerSets().computeIfAbsent(MARKER_SET_ID,
                    k -> MarkerSet.builder().label(label).toggleable(true).defaultHidden(hidden).build());
            set.setLabel(label);
            set.setDefaultHidden(hidden);
            Map<String, Marker> wanted = byWorld.getOrDefault(map.getWorld().getId(), Map.of());
            Map<String, Marker> current = set.getMarkers();
            current.keySet().retainAll(wanted.keySet());
            current.putAll(wanted);
        }

        int linked = (int) owners.values().stream().filter(b -> b).count();
        return new Result(zones, owners.size(), linked);
    }

    static void clear(BlueMapAPI blueMap) {
        for (BlueMapMap map : blueMap.getMaps()) {
            map.getMarkerSets().remove(MARKER_SET_ID);
        }
    }

    private static String detailHtml(Group group, Map<UUID, Profile> discord) {
        Owner owner = group.owner();
        StringBuilder h = new StringBuilder(512);
        h.append("<div style=\"min-width:180px;line-height:1.4\">");
        h.append("<div style=\"font-weight:bold;font-size:1.1em;border-left:4px solid ")
                .append(String.format("#%06X", group.color())).append(";padding-left:6px\">")
                .append(esc(group.name())).append("</div>");

        switch (owner.kind()) {
            case SERVER -> h.append("<div style=\"opacity:.8\">Zona protegida del servidor</div>");
            case EXPIRED -> h.append("<div style=\"opacity:.8\">Claim caducado (jugador inactivo)</div>");
            case PLAYER -> {
                h.append("<div style=\"display:flex;align-items:center;gap:6px;margin-top:6px\">");
                Profile p = discord.get(owner.id());
                if (p != null && ClaimsConfig.DISCORD_AVATARS.get()) {
                    h.append("<img src=\"").append(esc(p.avatarUrl()))
                            .append("\" width=\"32\" height=\"32\" style=\"border-radius:50%\" alt=\"\">");
                }
                h.append("<div><div>Dueño: <b>").append(esc(owner.name())).append("</b></div>");
                if (p != null) h.append("<div style=\"opacity:.8\">").append(discordLabel(p)).append("</div>");
                h.append("</div></div>");
            }
        }

        Party party = owner.party();
        if (party != null && ClaimsConfig.SHOW_PARTY.get()) {
            h.append("<div style=\"margin-top:6px\">Party: <b>").append(esc(party.name())).append("</b>");
            if (ClaimsConfig.SHOW_PARTY_MEMBERS.get() && !party.members().isEmpty()) {
                h.append("<ul style=\"margin:2px 0 0 0;padding-left:18px\">");
                for (Member m : party.members()) {
                    h.append("<li>").append(esc(m.name()));
                    if (m.owner()) h.append(" <span style=\"opacity:.7\">(líder)</span>");
                    Profile mp = discord.get(m.id());
                    if (mp != null) h.append(" <span style=\"opacity:.7\">").append(discordLabel(mp)).append("</span>");
                    h.append("</li>");
                }
                h.append("</ul>");
            }
            h.append("</div>");
        }

        h.append("<div style=\"margin-top:6px;opacity:.7;font-size:.9em\">")
                .append(group.chunks().size()).append(group.chunks().size() == 1 ? " chunk" : " chunks")
                .append(" aquí · ").append(owner.claimCount()).append(" en total");
        if (ClaimsConfig.SHOW_FORCELOADED.get() && owner.forceloadCount() > 0) {
            h.append(" · ").append(owner.forceloadCount()).append(" forzados");
        }
        h.append("</div></div>");
        return h.toString();
    }

    private static String discordLabel(Profile p) {
        String user = p.username() != null ? "@" + p.username() : p.discordId();
        if (p.globalName() != null && !p.globalName().isBlank() && !p.globalName().equals(p.username())) {
            return esc(p.globalName()) + " (" + esc(user) + ")";
        }
        return esc(user);
    }

    /** Claim, party and Discord names are typed by players: never put them in the page unescaped. */
    static String esc(@Nullable String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
