// Minimal app-shell service worker.
// - Caches static assets on install for instant load + basic offline.
// - NEVER caches /api/ or /ws/ — those must always hit the live backend.
// - On version bump, change CACHE to invalidate old shells.

const CACHE = 'haasst-v1';
const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icon.svg'];

self.addEventListener('install', (event) => {
  event.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))),
    ),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);

  // Never cache live API or WebSocket traffic.
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws/')) return;
  if (event.request.method !== 'GET') return;

  // Stale-while-revalidate for everything else.
  event.respondWith(
    caches.open(CACHE).then(async (cache) => {
      const cached = await cache.match(event.request);
      const network = fetch(event.request)
        .then((resp) => {
          if (resp.ok) cache.put(event.request, resp.clone());
          return resp;
        })
        .catch(() => cached);
      return cached || network;
    }),
  );
});
