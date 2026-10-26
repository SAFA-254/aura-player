// Кэширует только оболочку приложения, чтобы оно открывалось мгновенно.
// Запросы к Spotify никогда не кэшируются.
const CACHE = 'aura-v27';
const SHELL = ['./', './index.html', './style.css', './app.js', './manifest.webmanifest', './icons/icon-192.png', './icons/icon-512.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// Сначала сеть (чтобы обновления приходили сразу), без сети — из кэша.
self.addEventListener('fetch', (e) => {
  const req = e.request;
  if (req.method !== 'GET' || new URL(req.url).origin !== location.origin) return;
  // Если сеть думает дольше 4 секунд — открываемся из кэша, а не висим на белом экране
  const network = fetch(new Request(req.url, { cache: 'no-cache' })).then((res) => {
    if (res.ok) {
      const copy = res.clone();
      caches.open(CACHE).then((c) => c.put(req, copy));
    }
    return res;
  });
  const slow = new Promise((resolve) => setTimeout(resolve, 4000))
    .then(() => caches.match(req, { ignoreSearch: true }))
    .then((r) => r || network);
  e.respondWith(
    Promise.race([network, slow])
      .catch(() => caches.match(req, { ignoreSearch: true }).then((r) => r || caches.match('./'))),
  );
});
