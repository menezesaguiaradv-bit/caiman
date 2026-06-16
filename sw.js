// Service Worker — Caiman Agro
// Estrategia: rede primeiro para HTML (sempre versao nova), cache como reserva offline
const CACHE_NOME = 'caiman-agro-v3';
const ARQUIVOS = [
  './',
  './index.html',
  './manifest.json',
  'https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.full.min.js',
  'https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js'
];

// Instalacao — faz o cache dos arquivos
self.addEventListener('install', function(e) {
  e.waitUntil(
    caches.open(CACHE_NOME).then(function(cache) {
      return cache.addAll(ARQUIVOS).catch(function(err) {
        console.log('Cache parcial:', err);
      });
    })
  );
  self.skipWaiting();
});

// Ativacao — limpa TODOS os caches antigos
self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(nomes) {
      return Promise.all(
        nomes.filter(function(n) { return n !== CACHE_NOME; })
             .map(function(n) { return caches.delete(n); })
      );
    }).then(function() {
      return self.clients.claim();
    })
  );
});

// Interceptacao de requisicoes
self.addEventListener('fetch', function(e) {
  if (e.request.url.indexOf('supabase') !== -1) {
    return;
  }

  var url = e.request.url;
  var ehPaginaPrincipal = (e.request.mode === 'navigate') ||
                           url.indexOf('index.html') !== -1 ||
                           url.endsWith('/caiman/') ||
                           url.endsWith('/caiman');

  // REDE PRIMEIRO para a pagina principal (garante versao sempre atualizada)
  if (ehPaginaPrincipal) {
    e.respondWith(
      fetch(e.request).then(function(respRede) {
        if (respRede && respRede.status === 200) {
          var clone = respRede.clone();
          caches.open(CACHE_NOME).then(function(cache) {
            cache.put(e.request, clone);
          });
        }
        return respRede;
      }).catch(function() {
        return caches.match(e.request).then(function(r) {
          return r || caches.match('./index.html');
        });
      })
    );
    return;
  }

  // CACHE PRIMEIRO para os demais recursos
  e.respondWith(
    caches.match(e.request).then(function(resposta) {
      if (resposta) return resposta;
      return fetch(e.request).then(function(respRede) {
        if (!respRede || respRede.status !== 200 || respRede.type === 'error') {
          return respRede;
        }
        var clone = respRede.clone();
        caches.open(CACHE_NOME).then(function(cache) {
          cache.put(e.request, clone);
        });
        return respRede;
      });
    })
  );
});
