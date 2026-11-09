# KB BlueMap Claims (Forge 1.20.1)

Mod **solo de servidor** que dibuja en [BlueMap](https://bluemap.bluecolored.de/) los claims de [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) (OPaC).

- **Una zona por grupo de chunks**, con el nombre y el color que cada jugador pone a sus claims en OPaC. Los chunks sin reclamar rodeados por un claim salen como huecos. Los sub-claims con otro nombre o color salen como zonas aparte.
- **Al momento.** Se actualiza unos segundos después de reclamar o liberar un chunk, y cada pocos minutos por si cambia un nombre, un color o una party (de eso OPaC no avisa).
- **Popup al pinchar:** nombre del claim, dueño, su party con los miembros y el líder, y los chunks.
- **Con KB Discord Auth:** el `@` de Discord del dueño y de los miembros, y la foto de perfil del dueño. Solo lee `config/kb_discord_auth/links.json`; si el mod no está, simplemente no sale. De ese archivo solo se usan el id, el nombre y el avatar de Discord, nunca los tokens.
- **No da lag:** en el hilo del servidor solo se copia la lista de claims; las formas y el HTML se calculan en otro hilo.
- Los nombres que escriben los jugadores (claims, parties, Discord) se escapan, así que nadie puede meter HTML o scripts en la web del mapa.

Los clientes no necesitan el mod.

## Requisitos

| Mod | Versión |
|---|---|
| Forge | 47.x (Minecraft 1.20.1) |
| Open Parties and Claims | probado con 0.33.0 |
| BlueMap | 5.x (API 2.7). **5.3 si el servidor usa Java 17**; 5.12 necesita **Java 21** |
| KB Discord Auth | opcional |

## Compilar

`./gradlew build` → el jar queda en `build/libs/`. Requiere JDK 17.

## Comandos (permiso 2)

| Comando | Qué hace |
|---|---|
| `/kbclaims refresh` | Actualiza el mapa ya |
| `/kbclaims status` | Última actualización, zonas, dueños y cuántos tienen Discord vinculado |

## Config (`config/kb_bluemap_claims-common.toml`)

```toml
[refresh]
changeDelaySeconds = 5      # espera tras reclamar/liberar (junta varios cambios seguidos)
fullRefreshMinutes = 5      # repaso completo para nombres, colores y parties (0 = nunca)

[markers]
label = "Claims"            # nombre de la capa en BlueMap
defaultHidden = false
minY = 64.0                 # minY == maxY: zona plana; si no, un bloque de minY a maxY
maxY = 64.0
depthTest = false           # false = siempre visible por encima del terreno
fillOpacity = 0.35
lineWidth = 2
showServerClaims = true
showExpiredClaims = false

[popup]
showParty = true
showPartyMembers = true
showForceloaded = false

[discord]
enabled = true                          # ojo: si el mapa es público, cualquiera ve los @ de Discord
linksFile = "kb_discord_auth/links.json"
avatars = true                          # las fotos se cargan desde cdn.discordapp.com
```

## Créditos

La parte que convierte los chunks en polígonos (`shape/`) está portada de
[opac-bluemap-integration](https://github.com/Gaming32/opac-bluemap-integration) de Gaming32 (MIT), que solo existe para Fabric.
