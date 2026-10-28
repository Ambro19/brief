/*
 * Brief! service worker (2.3; notifications since 2.8).
 *
 * A service worker is a small script the browser keeps running beside the page. It sees the
 * page's network requests and may answer them itself. This one does two jobs: keep a copy of the
 * app's own files, so the installed app opens at once and still opens with no connection; and
 * (2.8) show the notifications the server sends when someone mentions the member, even when no
 * Brief! tab is open. See "Notifications" at the end of this file.
 *
 * What it handles, and how:
 *
 *   the page itself ("/")    Ask the network first, so a new release is picked up straight away.
 *                            If the answer takes more than three seconds, or there is no
 *                            connection, use the copy kept from last time.
 *   /assets/...              Use the kept copy if there is one. These files have a fingerprint of
 *                            their content in their name, so a kept copy can never be out of date.
 *
 * What it never touches: the API (posts, accounts, login), the API docs, and anything on another
 * site. Those always go to the network exactly as before. Posts are never served from a cache.
 *
 * This file is plain JavaScript on purpose. It is not part of the bundle: the browser fetches it
 * by its fixed address, /sw.js, and checks for a new version by itself.
 */

/** Change this name to make every browser throw away what it has kept and start again. */
const CACHE = "brief-shell-v1";
const PAGE = "/";
const PAGE_WAIT_MS = 3000;

self.addEventListener("install", (event) => {
  // Keep a first copy of the page and of the files it names, and take over without waiting
  // for old tabs to close. If keeping fails (no space, a network hiccup) the worker is still
  // installed; it will keep the files the next time they are fetched.
  event.waitUntil(
    (async () => {
      try {
        const cache = await caches.open(CACHE);
        const response = await fetch(new Request(PAGE, { cache: "reload" }));
        const html = await response.clone().text();
        if (response.ok && isTheApp(html)) {
          await cache.put(PAGE, response);
          await cache.addAll(assetsNamedIn(html));
        }
      } catch {
        /* nothing kept this time */
      }
      await self.skipWaiting();
    })(),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    (async () => {
      for (const name of await caches.keys()) {
        if (name !== CACHE) await caches.delete(name); // copies kept by an earlier version of this file
      }
      await self.clients.claim();
    })(),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;

  const isThePage = request.mode === "navigate" && (url.pathname === "/" || url.pathname === "/index.html");
  if (isThePage) {
    event.respondWith(pageFromNetworkFirst(event));
  } else if (url.pathname.startsWith("/assets/")) {
    event.respondWith(assetFromCacheFirst(request));
  }
  // Anything else: not answered here, so the browser fetches it from the network as usual.
});

/**
 * True if this HTML is Brief! itself. A hosting platform can answer the same address with a page
 * of its own (Render shows a "starting up" page while a sleeping service wakes). That page must
 * never replace the kept copy of the app.
 */
function isTheApp(html) {
  return html.includes('id="root"') && html.includes("/assets/");
}

/** The fingerprinted files a page refers to, for example /assets/index-BZ0PIfOj.js. */
function assetsNamedIn(html) {
  return [...new Set(html.match(/\/assets\/[A-Za-z0-9._-]+/g) ?? [])];
}

/** The page: fresh if the network answers in time, otherwise the kept copy. */
async function pageFromNetworkFirst(event) {
  const cache = await caches.open(CACHE);
  const kept = await cache.match(PAGE);

  const fresh = fetch(event.request).then(async (response) => {
    if (response.ok) {
      // Keeping a copy must never get in the way of showing the page, so its failures are swallowed.
      try {
        const html = await response.clone().text();
        if (isTheApp(html)) {
          await cache.put(PAGE, response.clone());
          await forgetAssetsNotNamedIn(html, cache);
        }
      } catch {
        /* not kept this time */
      }
    }
    return response;
  });
  // If the kept copy is what gets shown, still let the fresh one finish and be kept for next time.
  event.waitUntil(fresh.catch(() => {}));

  if (!kept) return fresh; // first visit: there is nothing else to show

  const tooSlow = new Promise((resolve) => setTimeout(() => resolve(null), PAGE_WAIT_MS));
  try {
    const response = await Promise.race([fresh, tooSlow]);
    return response && response.ok ? response : kept;
  } catch {
    return kept; // no connection
  }
}

/** A fingerprinted file: the kept copy if there is one, otherwise fetch it and keep it. */
async function assetFromCacheFirst(request) {
  const cache = await caches.open(CACHE);
  const kept = await cache.match(request);
  if (kept) return kept;
  const response = await fetch(request);
  if (response.ok) {
    try {
      await cache.put(request, response.clone());
    } catch {
      /* not kept this time; the file itself is still returned */
    }
  }
  return response;
}

/**
 * After a new release the old scripts and style sheets are no longer referred to. Drop them.
 * Fonts are named by the style sheet, not by the page, so they are left alone.
 */
async function forgetAssetsNotNamedIn(html, cache) {
  for (const request of await cache.keys()) {
    const path = new URL(request.url).pathname;
    const isCode = path.endsWith(".js") || path.endsWith(".css");
    if (path.startsWith("/assets/") && isCode && !html.includes(path)) await cache.delete(request);
  }
}

// ------------------------------------------------------------------ notifications (2.8)
//
// The server sends a small JSON document, encrypted for this browser (the browser decrypts it
// before this code sees it):
//
//   {"title": "Gilberto mentioned you", "body": "Hey @Mayayo, ...", "url": "/#/mentions",
//    "tag": "brief-mention", "message_id": 42}
//
// A newer notification with the same tag replaces the older one, so a burst of mentions leaves
// one notification, not a pile. Browsers require that every push shows something: a push that
// cannot be read still shows a plain "Brief!" notification.

self.addEventListener("push", (event) => {
  let content = {};
  try {
    content = event.data ? event.data.json() : {};
  } catch {
    content = {};
  }
  event.waitUntil(show(content));
});

/** Where a tap may lead: a page of this site, nothing else ("//other.site/" also starts with "/"). */
function sameSitePath(value) {
  try {
    const url = new URL(value, self.location.origin);
    return url.origin === self.location.origin ? url.pathname + url.search + url.hash : null;
  } catch {
    return null;
  }
}

function show(content) {
  const title = typeof content.title === "string" && content.title ? content.title : "Brief!";
  const url = (typeof content.url === "string" && sameSitePath(content.url)) || "/#/mentions";
  return self.registration.showNotification(title, {
    body: typeof content.body === "string" ? content.body : "Someone mentioned you.",
    tag: typeof content.tag === "string" ? content.tag : "brief-mention",
    renotify: true, // a replacement still makes a sound or a buzz, as a new mention should
    icon: "/icon-192.png",
    badge: "/icon-192.png",
    data: { url },
  });
}

// A tap on the notification: bring an open Brief! window forward and show the mentions there, or
// open a new one. Only addresses on this site are opened (the url is checked when it arrives).
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const target = new URL(sameSitePath(event.notification.data?.url ?? "") ?? "/#/mentions", self.location.origin).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin === self.location.origin && "focus" in client) {
          await client.focus();
          try {
            if ("navigate" in client) await client.navigate(target);
          } catch {
            /* a window this worker does not control cannot be steered; it is in front, at least */
          }
          return;
        }
      }
      await self.clients.openWindow(target);
    })(),
  );
});
