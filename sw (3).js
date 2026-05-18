const CACHE = "caiman-v3";

self.addEventListener("install", e => {
  self.skipWaiting();
  e.waitUntil(
    caches.open(CACHE).then(c => {
      return c.addAll(["/caiman/", "/caiman/index.html"]).catch(()=>{});
    })
  );
});

self.addEventListener("activate", e => {
  e.waitUntil(
    caches.keys().then(keys =>
      Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
    )
  );
  self.clients.claim();
});

self.addEventListener("fetch", e => {
  // Sempre tenta buscar da rede primeiro
  e.respondWith(
    fetch(e.request)
      .then(r => {
        // Salva no cache se for sucesso
        if(r && r.status === 200) {
          const clone = r.clone();
          caches.open(CACHE).then(c => c.put(e.request, clone));
        }
        return r;
      })
      .catch(() => {
        // Sem internet: usa cache
        return caches.match(e.request);
      })
  );
});