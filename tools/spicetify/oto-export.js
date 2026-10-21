// Oto · Exportar a Oto
// Extensión de Spicetify: guarda en un archivo todas tus listas de Spotify (también las privadas, las que sigues y
// «Canciones que te gustan») para importarlas en Oto desde Ajustes → Importar de Spotify.
// Instalar: tools/spicetify/README.md (install.ps1 en Windows, install.sh en Linux y macOS).
(async function otoExport() {
    if (!Spicetify?.Platform?.RootlistAPI || !Spicetify?.Menu) {
        setTimeout(otoExport, 300);
        return;
    }
    const { RootlistAPI, PlaylistAPI, LibraryAPI } = Spicetify.Platform;

    // "spotify:image:ab67…" → URL. Las listas sin portada propia traen un mosaico ("spotify:mosaic:a:b:c:d"): vale la primera.
    const image = (images) => {
        const i = images?.find((x) => x.label === "large") ?? images?.[0];
        const u = i?.url;
        if (!u) return null;
        if (u.startsWith("spotify:image:")) return "https://i.scdn.co/image/" + u.slice(14);
        if (u.startsWith("spotify:mosaic:")) return "https://i.scdn.co/image/" + u.slice(15).split(":")[0];
        return u;
    };

    const track = (t) => ({
        t: t.name,
        a: (t.artists ?? []).map((x) => x.name),
        al: t.album?.name ?? null,
        d: t.duration?.milliseconds ?? null,
    });

    // Oto tiene un solo nivel de carpetas: una lista dentro de carpetas anidadas va a la de más fuera.
    const flatten = (items, out = [], folder = null) => {
        for (const i of items) i.type === "folder" ? flatten(i.items, out, folder ?? i.name) : out.push({ ...i, folder });
        return out;
    };

    async function collect() {
        const lists = [];
        const liked = await LibraryAPI.getTracks({ limit: 100000, offset: 0 });
        lists.push({
            id: "spotify:collection:tracks",
            name: "Canciones que te gustan",
            liked: true,
            own: true,
            tracks: liked.items.filter((t) => t.type === "track").map(track),
        });
        const root = await RootlistAPI.getContents();
        for (const p of flatten(root.items)) {
            if (p.type !== "playlist") continue;
            const c = await PlaylistAPI.getContents(p.uri, { limit: 100000 });
            lists.push({
                id: p.uri,
                name: p.name,
                own: !!p.isOwnedBySelf,
                folder: p.folder,
                image: image(p.images),
                tracks: c.items.filter((t) => t.type === "track").map(track),
            });
        }
        return lists;
    }

    async function run() {
        Spicetify.showNotification("Oto: leyendo tus listas…");
        try {
            const lists = await collect();
            const songs = lists.reduce((n, l) => n + l.tracks.length, 0);
            const json = JSON.stringify({ oto: 1, from: "spotify", at: new Date().toISOString(), lists });
            const a = document.createElement("a");
            a.href = URL.createObjectURL(new Blob([json], { type: "application/json" }));
            a.download = "oto-spotify.json";
            document.body.appendChild(a);
            a.click();
            a.remove();
            Spicetify.showNotification(`Oto: ${lists.length} listas y ${songs} canciones en Descargas/oto-spotify.json`);
        } catch (e) {
            console.error("[oto-export]", e);
            Spicetify.showNotification("Oto: no se pudo exportar (" + (e?.message ?? e) + ")", true);
        }
    }

    window.otoExport = run;
    new Spicetify.Menu.Item("Exportar a Oto", false, run).register();
})();
