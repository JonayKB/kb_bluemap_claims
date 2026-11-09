package es.jonaykb.kb_bluemap_claims;

import net.minecraftforge.common.ForgeConfigSpec;

/** config/kb_bluemap_claims-common.toml */
public final class ClaimsConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue CHANGE_DELAY_SECONDS;
    public static final ForgeConfigSpec.IntValue FULL_REFRESH_MINUTES;

    public static final ForgeConfigSpec.ConfigValue<String> MARKER_SET_LABEL;
    public static final ForgeConfigSpec.BooleanValue DEFAULT_HIDDEN;
    public static final ForgeConfigSpec.DoubleValue MIN_Y;
    public static final ForgeConfigSpec.DoubleValue MAX_Y;
    public static final ForgeConfigSpec.BooleanValue DEPTH_TEST;
    public static final ForgeConfigSpec.DoubleValue FILL_OPACITY;
    public static final ForgeConfigSpec.IntValue LINE_WIDTH;
    public static final ForgeConfigSpec.BooleanValue SHOW_SERVER_CLAIMS;
    public static final ForgeConfigSpec.BooleanValue SHOW_EXPIRED_CLAIMS;

    public static final ForgeConfigSpec.BooleanValue SHOW_PARTY;
    public static final ForgeConfigSpec.BooleanValue SHOW_PARTY_MEMBERS;
    public static final ForgeConfigSpec.BooleanValue SHOW_FORCELOADED;

    public static final ForgeConfigSpec.BooleanValue DISCORD_ENABLED;
    public static final ForgeConfigSpec.ConfigValue<String> DISCORD_LINKS_FILE;
    public static final ForgeConfigSpec.BooleanValue DISCORD_AVATARS;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Cuándo se actualizan los claims en BlueMap").push("refresh");
        CHANGE_DELAY_SECONDS = b
                .comment("Segundos que espera tras reclamar o liberar un chunk antes de actualizar el mapa.",
                        "Junta varios cambios seguidos (p. ej. al reclamar un área) en una sola actualización.")
                .defineInRange("changeDelaySeconds", 5, 1, 3600);
        FULL_REFRESH_MINUTES = b
                .comment("Cada cuántos minutos se actualiza todo aunque no haya cambios de chunks.",
                        "Recoge cambios de nombre, color y party, que OPaC no avisa. 0 = nunca.")
                .defineInRange("fullRefreshMinutes", 5, 0, 1440);
        b.pop();

        b.comment("Aspecto de las zonas en el mapa").push("markers");
        MARKER_SET_LABEL = b
                .comment("Nombre de la capa en el menú de BlueMap.")
                .define("label", "Claims");
        DEFAULT_HIDDEN = b
                .comment("Si la capa sale oculta al abrir el mapa.")
                .define("defaultHidden", false);
        MIN_Y = b
                .comment("Altura de las zonas. Si minY y maxY son iguales se dibujan planas;",
                        "si no, como un bloque de minY a maxY.")
                .defineInRange("minY", 64.0, -64.0, 320.0);
        MAX_Y = b.defineInRange("maxY", 64.0, -64.0, 320.0);
        DEPTH_TEST = b
                .comment("false = las zonas se ven siempre por encima del terreno.")
                .define("depthTest", false);
        FILL_OPACITY = b
                .comment("Opacidad del relleno, de 0 a 1. El borde va siempre opaco.")
                .defineInRange("fillOpacity", 0.35, 0.0, 1.0);
        LINE_WIDTH = b.defineInRange("lineWidth", 2, 1, 10);
        SHOW_SERVER_CLAIMS = b
                .comment("Mostrar los claims del servidor (los del admin con /openpac-claims server).")
                .define("showServerClaims", true);
        SHOW_EXPIRED_CLAIMS = b
                .comment("Mostrar los claims caducados de jugadores inactivos.")
                .define("showExpiredClaims", false);
        b.pop();

        b.comment("Qué sale al pinchar en una zona").push("popup");
        SHOW_PARTY = b.define("showParty", true);
        SHOW_PARTY_MEMBERS = b.define("showPartyMembers", true);
        SHOW_FORCELOADED = b
                .comment("Mostrar cuántos chunks tiene forzados (cargados siempre).")
                .define("showForceloaded", false);
        b.pop();

        b.comment("Integración con KB Discord Auth (opcional: si no está, se ignora)").push("discord");
        DISCORD_ENABLED = b
                .comment("Mostrar el @ de Discord del dueño y de los miembros de la party.",
                        "Ojo: si el mapa es público, cualquiera con el enlace lo verá.")
                .define("enabled", true);
        DISCORD_LINKS_FILE = b
                .comment("Archivo de vínculos de KB Discord Auth, relativo a config/. Solo se lee.")
                .define("linksFile", "kb_discord_auth/links.json");
        DISCORD_AVATARS = b
                .comment("Mostrar la foto de perfil de Discord del dueño (se carga desde cdn.discordapp.com).")
                .define("avatars", true);
        b.pop();

        SPEC = b.build();
    }

    private ClaimsConfig() {}
}
