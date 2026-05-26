// Service Worker — Caiman Agro
// Permite que o app funcione offline fazendo cache dos arquivos essenciais
const CACHE_NOME = 'caiman-agro-v1';
const ARQUIVOS = [
  './',
  './index.html',
  './manifest.json',
  'https://cdnjs.cloudflare.com/ajax/libs/xlsx/0.18.5/xlsx.full.min.js',
  'https://cdnjs.cloudflare.com/ajax/libs/jspdf/2.5.1/jspdf.umd.min.js'
];

// Instalação — faz o cache dos arquivos
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

// Ativação — limpa caches antigos
self.addEventListener('activate', function(e) {
  e.waitUntil(
    caches.keys().then(function(nomes) {
      return Promise.all(
        nomes.filter(function(n) { return n !== CACHE_NOME; })
             .map(function(n) { return caches.delete(n); })
      );
    })
  );
  self.clients.claim();
});

// Interceptação de requisições
self.addEventListener('fetch', function(e) {
  // Nunca cachear chamadas ao Supabase (dados sempre frescos)
  if (e.request.url.indexOf('supabase') !== -1) {
    return;
  }
  e.respondWith(
    caches.match(e.request).then(function(resposta) {
      // Se está no cache, retorna do cache
      if (resposta) return resposta;
      // Senão, busca na rede e cacheia
      return fetch(e.request).then(function(respRede) {
        if (!respRede || respRede.status !== 200 || respRede.type === 'error') {
          return respRede;
        }
        var clone = respRede.clone();
        caches.open(CACHE_NOME).then(function(cache) {
          cache.put(e.request, clone);
        });
        return respRede;
      }).catch(function() {
        // Offline e sem cache — retorna o index para navegação
        if (e.request.mode === 'navigate') {
          return caches.match('./index.html');
        }
      });
    })
  );
});
