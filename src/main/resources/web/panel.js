/*
 * KB BlueMap Claims · player panel.
 * Log in with Discord (KB Discord Auth), see your health and your party's, centre on / follow your
 * character and see your last death. Without logging in the map works exactly as before.
 * window.KB_MAP_PANEL = {authUrl, pollSeconds, deathMarker} is prepended by the server.
 */
(function () {
    "use strict";
    const cfg = window.KB_MAP_PANEL || {};
    const app = window.bluemap;
    if (!cfg.authUrl || !app || !window.BlueMap) return;

    const TOKEN_KEY = "kbMapPanel.token";
    const ACCOUNT_KEY = "kbMapPanel.account";
    const COLLAPSED_KEY = "kbMapPanel.collapsed";
    const DEATH_MARKER_ID = "kb-panel-death";
    const DEATH_CLASSES = ["kbp-death-marker"];
    const pollMs = Math.max(1, cfg.pollSeconds || 2) * 1000;

    // ───────────── storage (can throw in private windows) ─────────────
    const store = {
        get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } },
        set(k, v) { try { v == null ? localStorage.removeItem(k) : localStorage.setItem(k, v); } catch (e) { /* ignore */ } },
    };

    // ───────────── state ─────────────
    let token = store.get(TOKEN_KEY);
    let user = null;            // {id, username, globalName, avatarUrl}
    let data = null;            // last /web/api/map answer
    let error = null;           // text shown under the header
    let follow = null;          // {uuid, marker, lastPos}
    let pollTimer = null;
    let collapsed = store.get(COLLAPSED_KEY) === "1";

    // ───────────── DOM helpers (textContent only: names come from players) ─────────────
    function h(tag, attrs, ...children) {
        const el = document.createElement(tag);
        for (const [k, v] of Object.entries(attrs || {})) {
            if (v == null || v === false) continue;
            if (k.startsWith("on")) el.addEventListener(k.substring(2), v);
            else if (k === "class") el.className = v;
            else el.setAttribute(k, v === true ? "" : v);
        }
        for (const c of children.flat()) {
            if (c == null || c === false) continue;
            el.appendChild(typeof c === "string" || typeof c === "number" ? document.createTextNode(String(c)) : c);
        }
        return el;
    }

    const ICONS = {
        discord: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M20.32 4.37A19.8 19.8 0 0 0 15.4 2.85a13.7 13.7 0 0 0-.63 1.3 18.4 18.4 0 0 0-5.53 0 12.6 12.6 0 0 0-.64-1.3 19.7 19.7 0 0 0-4.93 1.52C.54 9.06-.3 13.63.12 18.13a19.9 19.9 0 0 0 6.04 3.05c.49-.66.92-1.36 1.3-2.1a12.9 12.9 0 0 1-2.04-.98l.5-.39a14.2 14.2 0 0 0 12.18 0l.5.39c-.65.39-1.33.71-2.05.98.38.74.81 1.44 1.3 2.1a19.8 19.8 0 0 0 6.05-3.05c.5-5.22-.84-9.75-3.58-13.76ZM8.02 15.37c-1.18 0-2.16-1.09-2.16-2.42 0-1.33.95-2.42 2.16-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.33-.95 2.42-2.16 2.42Zm7.97 0c-1.18 0-2.16-1.09-2.16-2.42 0-1.33.95-2.42 2.16-2.42 1.21 0 2.18 1.1 2.16 2.42 0 1.33-.95 2.42-2.16 2.42Z"/></svg>',
        target: '<svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="7" fill="none" stroke="currentColor" stroke-width="2"/><circle cx="12" cy="12" r="2.5" fill="currentColor"/><path d="M12 1v4M12 19v4M1 12h4M19 12h4" stroke="currentColor" stroke-width="2"/></svg>',
        follow: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 2 4.5 20.3l.7.7L12 18l6.8 3 .7-.7z"/></svg>',
        skull: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M12 2C6.9 2 3 5.6 3 10.3c0 2.7 1.3 5 3.4 6.5V20a2 2 0 0 0 2 2h7.2a2 2 0 0 0 2-2v-3.2c2.1-1.5 3.4-3.8 3.4-6.5C21 5.6 17.1 2 12 2Zm-3.5 11.5a2 2 0 1 1 0-4 2 2 0 0 1 0 4Zm7 0a2 2 0 1 1 0-4 2 2 0 0 1 0 4ZM10.5 19v-2h1v2h-1Zm2 0v-2h1v2h-1Z"/></svg>',
        chevron: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2.5" d="m6 9 6 6 6-6"/></svg>',
        logout: '<svg viewBox="0 0 24 24" aria-hidden="true"><path fill="none" stroke="currentColor" stroke-width="2" d="M15 4h4v16h-4M10 8l-4 4 4 4M6 12h10"/></svg>',
    };
    function icon(name) {
        const span = h("span", {class: "kbp-icon"});
        span.innerHTML = ICONS[name];
        return span;
    }

    const DEATH_ICON = "data:image/svg+xml," + encodeURIComponent(
        '<svg xmlns="http://www.w3.org/2000/svg" width="30" height="30" viewBox="0 0 30 30">' +
        '<circle cx="15" cy="15" r="13" fill="#1d1d1d" stroke="#ff5555" stroke-width="2.5"/>' +
        '<path fill="#ff5555" d="M15 7c-4 0-7 2.8-7 6.4 0 2 1 3.7 2.6 4.8V21a1 1 0 0 0 1 1h6.8a1 1 0 0 0 1-1v-2.8c1.6-1.1 2.6-2.8 2.6-4.8C22 9.8 19 7 15 7Zm-2.8 8.6a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3Zm5.6 0a1.5 1.5 0 1 1 0-3 1.5 1.5 0 0 1 0 3Z"/>' +
        '</svg>');

    // ───────────── network ─────────────
    async function call(path, options) {
        const headers = Object.assign({}, (options && options.headers) || {});
        if (token) headers.Authorization = "Bearer " + token;
        const res = await fetch(cfg.authUrl + path, Object.assign({}, options, {headers, cache: "no-store"}));
        if (res.status === 401) {
            setToken(null);
            throw new Error("logout");
        }
        if (!res.ok) throw new Error("http " + res.status);
        return res.status === 204 ? null : res.json();
    }

    function setToken(t) {
        token = t;
        store.set(TOKEN_KEY, t);
        if (!t) {
            user = null;
            data = null;
            stopFollow();
            updateDeathMarker();
        }
    }

    function login() {
        const url = cfg.authUrl + "/web/login?return=" + encodeURIComponent(location.href);
        location.href = url;
    }

    async function logout() {
        try { await call("/web/logout", {method: "POST"}); } catch (e) { /* ignore */ }
        setToken(null);
        render();
    }

    /** Back from Discord: ?kbauth=CODE → session token. */
    async function finishLogin() {
        const url = new URL(location.href);
        const code = url.searchParams.get("kbauth");
        const err = url.searchParams.get("kbauth_error");
        if (!code && !err) return;
        url.searchParams.delete("kbauth");
        url.searchParams.delete("kbauth_error");
        history.replaceState(history.state, "", url.pathname + url.search + location.hash);
        if (err) {
            error = err === "cancelled" ? "Has cancelado el inicio de sesión." : "No se pudo contactar con Discord.";
            return;
        }
        try {
            const res = await fetch(cfg.authUrl + "/web/session", {method: "POST", body: code, cache: "no-store"});
            if (!res.ok) throw new Error("http " + res.status);
            const json = await res.json();
            setToken(json.token);
            user = json.user;
            collapsed = false;
            store.set(COLLAPSED_KEY, null);
        } catch (e) {
            error = "El inicio de sesión ha caducado. Vuelve a intentarlo.";
        }
    }

    async function poll() {
        clearTimeout(pollTimer);
        if (!token) return;
        try {
            if (!user) user = (await call("/web/me")).user;
            const account = store.get(ACCOUNT_KEY);
            data = await call("/web/api/map" + (account ? "?account=" + encodeURIComponent(account) : ""));
            error = null;
        } catch (e) {
            if (e.message !== "logout") error = "Sin conexión con el servidor.";
        }
        render();
        updateDeathMarker();
        tickFollow();
        if (token) pollTimer = setTimeout(poll, document.hidden ? pollMs * 5 : pollMs);
    }

    // ───────────── map helpers ─────────────
    function currentMapId() {
        return app.mapViewer.map ? app.mapViewer.map.data.id : null;
    }

    function controls() {
        return app.mapViewer.controlsManager;
    }

    function liveMarker(uuid) {
        const m = app.playerMarkerManager && app.playerMarkerManager.getPlayerMarker(uuid);
        return m && m.visible && !m.data.foreign ? m : null;
    }

    /** Switches to a map of the target's dimension if the current one isn't. False if no map shows it. */
    async function showDimension(target) {
        const maps = target.maps || [];
        if (maps.length === 0) {
            toast("Esa dimensión no está en el mapa.");
            return false;
        }
        if (!maps.includes(currentMapId())) {
            app.mainMenu.closeAll();
            await app.switchMap(maps[0], false);
        }
        return true;
    }

    async function centerOn(target) {
        stopFollow();
        if (!target || !target.online && target.x == null) return;
        if (!(await showDimension(target))) return;
        const cm = controls();
        cm.position.set(target.x, target.y, target.z);
        if (cm.distance > 300) cm.distance = 120;
        render();
    }

    async function startFollow(target) {
        if (follow && follow.uuid === target.uuid) {
            stopFollow();
            render();
            return;
        }
        stopFollow();
        if (!target.online) return;
        if (!(await showDimension(target))) return;
        follow = {uuid: target.uuid, marker: null, lastPos: null};
        const cm = controls();
        if (cm.distance > 300) cm.distance = 120;
        tickFollow();
        render();
    }

    /** BlueMap keeps the marker's (reactive) data object in followingPlayer, never the marker itself. */
    function blueMapFollows(uuid) {
        const c = controls().controls;
        const fp = c && c.data && c.data.followingPlayer;
        return !!fp && fp.playerUuid === uuid;
    }

    function stopFollow() {
        if (!follow) return;
        const c = controls().controls;
        if (follow.marker && blueMapFollows(follow.uuid) && c.stopFollowingPlayerMarker) c.stopFollowingPlayerMarker();
        follow = null;
    }

    function findStatus(uuid) {
        if (!data) return null;
        if (data.player && data.player.uuid === uuid) return data.player;
        const members = data.party ? data.party.members : [];
        return members.find(m => m.uuid === uuid) || null;
    }

    /** Keeps following across dimension changes, and through BlueMap hiding the marker (sneaking…). */
    async function tickFollow() {
        if (!follow) return;
        const target = findStatus(follow.uuid);
        if (!target || !target.online || target.hiddenLocation) {
            stopFollow();
            toast(target && target.online ? "Ha dejado de compartir su ubicación." : "El jugador se ha desconectado.");
            render();
            return;
        }
        if (!(target.maps || []).includes(currentMapId())) {
            follow.marker = null;
            follow.lastPos = null;
            if (!(await showDimension(target))) stopFollow();
            return;
        }
        const c = controls().controls;
        const marker = liveMarker(follow.uuid);
        if (marker && c && c.followPlayerMarker) {
            if (follow.marker && !blueMapFollows(follow.uuid)) {
                // BlueMap drops the follow when you drag the map: respect that
                follow = null;
                render();
                return;
            }
            if (!blueMapFollows(follow.uuid)) c.followPlayerMarker(marker);
            follow.marker = marker;
            follow.lastPos = null;
            return;
        }
        // No marker (hidden by BlueMap): move the camera ourselves with each update
        const pos = controls().position;
        if (follow.lastPos && Math.hypot(pos.x - follow.lastPos.x, pos.z - follow.lastPos.z) > 1) {
            stopFollow();
            render();
            return;
        }
        follow.marker = null;
        pos.set(target.x, target.y, target.z);
        follow.lastPos = {x: target.x, z: target.z};
    }

    // ───────────── death marker (only in this browser) ─────────────
    // BlueMap deletes every marker set of the map root that isn't in its markers.json, except the player set and
    // "bm-popup-set". So the marker lives in BlueMap's popup set, handled as an object (never updateMarkersFromData
    // there: it would delete BlueMap's own popup).
    let deathMarker = null;
    function updateDeathMarker() {
        const death = cfg.deathMarker && data && data.player ? data.player.death : null;
        const set = app.popupMarkerSet;
        if (!set) return;
        if (!death || !(death.maps || []).includes(currentMapId())) {
            if (deathMarker) set.remove(deathMarker);
            deathMarker = null;
            return;
        }
        if (!deathMarker) {
            deathMarker = new window.BlueMap.PoiMarker(DEATH_MARKER_ID);
            set.add(deathMarker);
        }
        const when = death.time ? " · " + ago(death.time) : "";
        deathMarker.updateFromData({
            position: {x: death.x, y: death.y, z: death.z},
            label: "Tu última muerte",
            detail: '<div class="kbp-death-label"><b>Tu última muerte</b><br>' +
                Math.round(death.x) + ", " + Math.round(death.y) + ", " + Math.round(death.z) + escapeHtml(when) + "</div>",
            icon: DEATH_ICON,
            anchor: {x: 15, y: 15},
            classes: DEATH_CLASSES, // same array every time, or BlueMap rebuilds the classes on each update
            listed: false,
        });
    }

    function escapeHtml(s) {
        return String(s).replace(/[&<>"']/g, c => ({"&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"}[c]));
    }

    function ago(time) {
        const s = Math.max(0, (Date.now() - time) / 1000);
        if (s < 60) return "hace un momento";
        if (s < 3600) return "hace " + Math.floor(s / 60) + " min";
        if (s < 86400) return "hace " + Math.floor(s / 3600) + " h";
        return "hace " + Math.floor(s / 86400) + " d";
    }

    // ───────────── UI ─────────────
    const root = h("div", {id: "kb-map-panel", class: "kbp", role: "region", "aria-label": "Tu personaje"});
    document.body.appendChild(root);

    let toastTimer = null;
    function toast(text) {
        let el = document.getElementById("kbp-toast");
        if (!el) {
            el = h("div", {id: "kbp-toast", class: "kbp-toast", role: "status"});
            document.body.appendChild(el);
            syncTheme();
        }
        el.textContent = text;
        el.classList.add("kbp-show");
        clearTimeout(toastTimer);
        toastTimer = setTimeout(() => el.classList.remove("kbp-show"), 3000);
    }

    const DIMENSIONS = {
        "minecraft:overworld": "Superficie",
        "minecraft:the_nether": "Nether",
        "minecraft:the_end": "End",
    };
    function dimensionName(dim) {
        if (!dim) return "";
        if (DIMENSIONS[dim]) return DIMENSIONS[dim];
        const path = dim.substring(dim.indexOf(":") + 1).replace(/_/g, " ");
        return path.charAt(0).toUpperCase() + path.substring(1);
    }

    /** version: changes with the skin (BlueMap serves heads with a one-day cache). */
    function headUrl(uuid, version) {
        const map = app.mapViewer.map;
        if (!map) return "assets/steve.png";
        return map.data.dataUrl + "assets/playerheads/" + uuid + ".png" + (version ? "?v=" + encodeURIComponent(version) : "");
    }

    // One <img> per head, reused on every redraw: recreating it every few seconds made it flicker and, for players
    // without a head yet (404), re-requested it each time. A missing head shows Steve and is retried after a minute.
    const HEAD_RETRY_MS = 60000;
    const heads = new Map(); // url → {img, failedAt}
    function head(s) {
        const url = headUrl(s.uuid, s.head);
        let entry = heads.get(url);
        if (entry && entry.failedAt && Date.now() - entry.failedAt > HEAD_RETRY_MS) {
            entry.failedAt = 0;
            entry.img.src = url + (url.includes("?") ? "&" : "?") + "r=" + Date.now();
        }
        if (!entry) {
            const img = h("img", {class: "kbp-head", alt: ""});
            entry = {img, failedAt: 0};
            img.addEventListener("error", () => {
                if (img.src.endsWith("assets/steve.png")) return;
                entry.failedAt = Date.now();
                img.src = "assets/steve.png";
            });
            img.src = url;
            heads.set(url, entry);
        }
        // A node can only be in one place: if the same head is already used in this redraw, use a copy
        if (rendering.has(entry.img)) return entry.img.cloneNode();
        rendering.add(entry.img);
        return entry.img;
    }
    let rendering = new Set(); // heads used in the current redraw

    /** Ten hearts like in the game, whatever the max health, plus the numbers. */
    function hearts(s) {
        const max = Math.max(1, s.maxHealth || 20);
        const hp = Math.max(0, Math.min(s.health || 0, max));
        const frac = hp / max;
        const row = h("div", {class: "kbp-hearts", role: "img", "aria-label": hp + " de " + max + " de vida"});
        for (let i = 0; i < 10; i++) {
            const fill = Math.max(0, Math.min(1, frac * 10 - i));
            const cls = fill >= 0.75 ? "full" : fill >= 0.25 ? "half" : "empty";
            row.appendChild(h("span", {class: "kbp-heart " + cls}));
        }
        const text = h("span", {class: "kbp-hp"}, fmt(hp) + "/" + fmt(max));
        if (s.absorption > 0) text.appendChild(h("span", {class: "kbp-abs"}, " +" + fmt(s.absorption)));
        const low = frac <= 0.3;
        return h("div", {class: "kbp-health" + (low ? " kbp-low" : "")}, row, text);
    }

    function fmt(n) {
        return Number.isInteger(n) ? String(n) : n.toFixed(1);
    }

    function statusLine(s) {
        if (!s.online) return h("div", {class: "kbp-sub kbp-offline"}, "Desconectado");
        if (s.dead) return h("div", {class: "kbp-sub kbp-dead"}, "Muerto");
        if (s.hiddenLocation) return h("div", {class: "kbp-sub"}, "No comparte su ubicación");
        return h("div", {class: "kbp-sub"},
            h("span", {class: "kbp-dim kbp-dim-" + (s.dimension || "").replace(/[^a-z_]/g, "_")}, dimensionName(s.dimension)),
            " ", h("span", {class: "kbp-coords"}, Math.round(s.x) + ", " + Math.round(s.y) + ", " + Math.round(s.z)));
    }

    function memberRow(s) {
        const following = follow && follow.uuid === s.uuid;
        const locatable = s.online && !s.hiddenLocation;
        return h("li", {class: "kbp-member" + (s.online ? "" : " kbp-is-offline")},
            h("button", {class: "kbp-member-main", "data-k": "m-" + s.uuid, disabled: !locatable, title: locatable ? "Centrar en " + s.name : null,
                    onclick: () => centerOn(s)},
                head(s),
                h("div", {class: "kbp-member-info"},
                    h("div", {class: "kbp-name"}, s.name, s.owner ? h("span", {class: "kbp-badge", title: "Líder de la party"}, "Líder") : null),
                    s.online && !s.dead && !s.spectator ? hearts(s) : statusLine(s),
                    s.online && !s.dead ? statusLine(s) : null)),
            locatable ? h("button", {class: "kbp-icon-btn" + (following ? " kbp-active" : ""), "data-k": "f-" + s.uuid, title: following ? "Dejar de seguir" : "Seguir a " + s.name,
                "aria-pressed": following ? "true" : "false", onclick: () => startFollow(s)}, icon("follow")) : null);
    }

    function render() {
        // Don't close the account list while it is open
        if (root.contains(document.activeElement) && document.activeElement.tagName === "SELECT") return;
        // Redrawn every few seconds: put the keyboard focus back where it was
        const focused = root.contains(document.activeElement) ? document.activeElement.dataset.k : null;
        draw();
        if (focused) {
            const el = root.querySelector('[data-k="' + focused + '"]');
            if (el && !el.disabled) el.focus({preventScroll: true});
        }
    }

    function draw() {
        rendering = new Set();
        root.replaceChildren();
        root.classList.toggle("kbp-collapsed", collapsed);

        if (!token) {
            root.appendChild(h("button", {class: "kbp-login", "data-k": "login", onclick: login},
                icon("discord"), h("span", {}, "Iniciar sesión")));
            if (error) root.appendChild(h("div", {class: "kbp-error"}, error));
            return;
        }

        const me = data && data.player;
        const title = me ? me.name : user ? (user.globalName || user.username) : "…";
        const header = h("div", {class: "kbp-header"},
            h("button", {class: "kbp-toggle", "data-k": "toggle", "aria-expanded": collapsed ? "false" : "true",
                    title: collapsed ? "Mostrar panel" : "Ocultar panel",
                    onclick: () => { collapsed = !collapsed; store.set(COLLAPSED_KEY, collapsed ? "1" : null); render(); }},
                user ? h("img", {class: "kbp-avatar", src: user.avatarUrl, alt: ""}) : null,
                h("span", {class: "kbp-title"}, title),
                collapsed && me && me.online ? h("span", {class: "kbp-mini-hp"}, "❤ " + fmt(me.health)) : null,
                h("span", {class: "kbp-chevron"}, icon("chevron"))),
            h("button", {class: "kbp-icon-btn kbp-logout", "data-k": "logout", title: "Cerrar sesión", onclick: logout}, icon("logout")));
        root.appendChild(header);
        if (collapsed) return;

        const body = h("div", {class: "kbp-body"});
        root.appendChild(body);
        if (error) body.appendChild(h("div", {class: "kbp-error"}, error));
        if (!data) {
            body.appendChild(h("div", {class: "kbp-empty"}, "Cargando…"));
            return;
        }
        if (!data.accounts || data.accounts.length === 0) {
            body.appendChild(h("div", {class: "kbp-empty"},
                "Tu Discord no tiene ninguna cuenta de Minecraft vinculada. Entra una vez al servidor para vincularla."));
            return;
        }

        if (data.accounts.length > 1) {
            const select = h("select", {class: "kbp-account", "aria-label": "Cuenta de Minecraft",
                onchange: e => { store.set(ACCOUNT_KEY, e.target.value); stopFollow(); data = null; render(); poll(); }});
            for (const a of data.accounts) {
                const opt = h("option", {value: a.uuid}, a.name + (a.online ? "" : " (desconectado)"));
                if (me && a.uuid === me.uuid) opt.selected = true;
                select.appendChild(opt);
            }
            body.appendChild(select);
        }

        // You
        const following = me && follow && follow.uuid === me.uuid;
        body.appendChild(h("section", {class: "kbp-me"},
            h("div", {class: "kbp-me-top"},
                head(me),
                h("div", {class: "kbp-member-info"},
                    me.online && !me.dead && !me.spectator ? hearts(me) : null,
                    statusLine(me))),
            h("div", {class: "kbp-actions"},
                h("button", {class: "kbp-btn", "data-k": "center", disabled: !me.online, onclick: () => centerOn(me)}, icon("target"), "Centrar"),
                h("button", {class: "kbp-btn" + (following ? " kbp-active" : ""), "data-k": "follow", disabled: !me.online, "aria-pressed": following ? "true" : "false",
                    onclick: () => startFollow(me)}, icon("follow"), following ? "Siguiendo" : "Seguir"),
                cfg.deathMarker ? h("button", {class: "kbp-btn", "data-k": "death", disabled: !me.death,
                    title: me.death ? "Ir a tu última muerte" : "Aún no has muerto",
                    onclick: () => centerOn(Object.assign({online: true}, me.death))}, icon("skull"), "Muerte") : null)));

        // Party
        if (data.party) {
            const members = data.party.members || [];
            // Count yourself too: "2/3 conectados" is about the whole party
            const online = members.filter(m => m.online).length + (me.online ? 1 : 0);
            body.appendChild(h("section", {class: "kbp-party"},
                h("div", {class: "kbp-section-title"},
                    h("span", {}, data.party.name),
                    h("span", {class: "kbp-count"}, online + "/" + (members.length + 1) + " conectados")),
                members.length === 0
                    ? h("div", {class: "kbp-empty"}, "Estás solo en tu party.")
                    : h("ul", {class: "kbp-members"}, members.map(memberRow))));
        } else {
            body.appendChild(h("div", {class: "kbp-empty kbp-hint"}, "No estás en ninguna party."));
        }
    }

    // BlueMap puts its theme classes (light / dark / contrast) on the map container, not on :root,
    // so copy them to the panel and the toast, which live outside it
    const THEMES = ["theme-light", "theme-dark", "theme-contrast"];
    function syncTheme() {
        const src = app.mapViewer.rootElement;
        for (const el of [root, document.getElementById("kbp-toast")]) {
            if (el) for (const t of THEMES) el.classList.toggle(t, src.classList.contains(t));
        }
    }
    new MutationObserver(syncTheme).observe(app.mapViewer.rootElement, {attributes: true, attributeFilter: ["class"]});
    syncTheme();

    // Re-check the death marker and follow when the user switches maps by hand
    app.events.addEventListener("bluemapMapChanged", () => {
        updateDeathMarker();
        render(); // player heads come from the current map
    });
    document.addEventListener("visibilitychange", () => { if (!document.hidden && token) poll(); });

    (async function start() {
        await finishLogin();
        render();
        poll();
    })();
})();
