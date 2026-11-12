# KB BlueMap Claims (Forge 1.20.1)

Mod **solo de servidor** que dibuja en [BlueMap](https://bluemap.bluecolored.de/) los claims de [Open Parties and Claims](https://modrinth.com/mod/open-parties-and-claims) (OPaC).

- **Una zona por grupo de chunks**, con el nombre y el color que cada jugador pone a sus claims en OPaC. Los chunks sin reclamar rodeados por un claim salen como huecos. Los sub-claims con otro nombre o color salen como zonas aparte.
- **Al momento.** Se actualiza unos segundos después de reclamar o liberar un chunk, y cada pocos minutos por si cambia un nombre, un color o una party (de eso OPaC no avisa).
- **Popup al pinchar:** nombre del claim, dueño, su party con los miembros y el líder, y los chunks.
- **Con KB Discord Auth:** el `@` de Discord del dueño y de los miembros, y la foto de perfil del dueño. Solo lee `config/kb_discord_auth/links.json`; si el mod no está, simplemente no sale. De ese archivo solo se usan el id, el nombre y el avatar de Discord, nunca los tokens.
- **Panel del jugador en la web (con KB Discord Auth).** Botón «Iniciar sesión» con Discord en la web de BlueMap. Con la sesión iniciada ves tu vida (corazones, absorción), dónde estás, y la vida y la dimensión de los de tu party de OPaC. Puedes **centrar** el mapa en ti o en un compañero, **seguir** a cualquiera (aunque cambie de dimensión) e **ir a tu última muerte**, que sale marcada en el mapa solo para ti. Sin iniciar sesión, la web es igual que siempre.
- **No da lag:** en el hilo del servidor solo se copia la lista de claims; las formas y el HTML se calculan en otro hilo.
- Los nombres que escriben los jugadores (claims, parties, Discord) se escapan, así que nadie puede meter HTML o scripts en la web del mapa.

Los clientes no necesitan el mod.

## Requisitos

| Mod | Versión |
|---|---|
| Forge | 47.x (Minecraft 1.20.1) |
| Open Parties and Claims | probado con 0.33.0 |
| BlueMap | 5.x (API 2.7). **5.3 si el servidor usa Java 17**; 5.12 necesita **Java 21** |
| KB Discord Auth | opcional; 1.6+ con `[web] enabled = true` para el panel del jugador |

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

Y para el panel del jugador:

```toml
[web]
playerPanel = true
authUrl = "https://auth.example.com"   # URL pública de KB Discord Auth, como la ve el navegador. Vacío = sin panel
pollSeconds = 2                        # cada cuánto se actualizan la vida y las posiciones
deathMarker = true                     # marcar tu última muerte (solo la ves tú)
```

## Panel del jugador

1. En KB Discord Auth: `[web] enabled = true` y la web del mapa en `allowedOrigins` (p. ej. `["https://minecraft.example.com"]`).
2. Aquí: `[web] authUrl` con la URL pública de KB Discord Auth (p. ej. `"https://auth.example.com"`).
3. Reinicia. Al cargar BlueMap se copian `assets/kb_bluemap_claims/panel.js` y `panel.css` a su webroot y se registran. No hay que tocar nginx ni BlueMap.

- **Qué ves:** tus cuentas vinculadas a ese Discord (si hay varias, se elige en una lista), tu vida, tu posición y dimensión, y los de tu party. Nada de otros jugadores que no sean de tu party.
- **Seguir:** se engancha a la función de seguir jugador de BlueMap. Si el jugador cambia de dimensión, el mapa cambia con él. Si BlueMap lo oculta (agachado, invisible…), la cámara se mueve con las posiciones del panel. Al arrastrar el mapa se deja de seguir.
- **Ubicación de la party:** se respeta la opción de OPaC `parties.shareLocationWithParty`. Quien la desactive sale con su vida pero sin posición, y no se le puede centrar ni seguir.
- **Última muerte:** la de Minecraft si estás conectado; si no, la que guarda este mod en `config/kb_bluemap_claims/deaths.json`.
- La sesión dura `sessionDays` (KB Discord Auth) y se guarda en el navegador. «Cerrar sesión» la borra también del servidor.

## Créditos

La parte que convierte los chunks en polígonos (`shape/`) está portada de
[opac-bluemap-integration](https://github.com/Gaming32/opac-bluemap-integration) de Gaming32 (MIT), que solo existe para Fabric.
