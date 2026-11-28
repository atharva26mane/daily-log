// Daily Log service worker: lets the app open offline.
// Network first (so new versions show up), cache as backup.
const CACHE = 'daily-log-v61';
const FILES = ['./', './index.html', './gsap.min.js', './ScrollTrigger.min.js', './manifest.webmanifest', './icon-192.png', './icon-512.png', './icon-maskable-192.png', './icon-maskable-512.png', './apple-touch-icon.png', './favicon-32.png', './badge-96.png'];

self.addEventListener('install', e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = new URL(req.url);
  const sameSite = url.origin === self.location.origin;
  const fonts = url.hostname === 'fonts.googleapis.com' || url.hostname === 'fonts.gstatic.com';
  if (!sameSite && !fonts) return; // never touch AI calls or anything else
  e.respondWith(
    fetch(req)
      .then(res => {
        if (res && (res.ok || res.type === 'opaque')) {
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put(req, copy));
        }
        return res;
      })
      .catch(() => caches.match(req).then(r => r || (sameSite ? caches.match('./index.html') : Response.error())))
  );
});

// Alerts (web push). Each alert opens the part of the app it is about.
function targetFor(d) {
  if (d.go) return d.go;
  const tag = String(d.tag || '');
  if (tag === 'water' || tag === 'bottle') return 'water';
  if (tag.startsWith('todo') || /^t\d/.test(tag)) return 'todo';
  if (/^c\d/.test(tag)) return 'reminders';
  if (tag.startsWith('lb')) return 'leaderboard';
  return '';
}
self.addEventListener('push', e => {
  let d = {};
  try { d = e.data ? e.data.json() : {}; } catch (_) {}
  const go = targetFor(d);
  e.waitUntil(self.registration.showNotification(d.title || 'Daily Log', {
    body: d.body || '', icon: 'icon-192.png', badge: 'badge-96.png', tag: d.tag || undefined, data: { go }
  }));
});
self.addEventListener('notificationclick', e => {
  e.notification.close();
  const go = (e.notification.data && e.notification.data.go) || '';
  e.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then(list => {
    for (const c of list) {
      if ('focus' in c) { if (go) c.postMessage({ type: 'go', go }); return c.focus(); }
    }
    return self.clients.openWindow(go ? './?go=' + encodeURIComponent(go) : './');
  }));
});
