/*
 * Brief! service worker (2.3).
 *
 * A service worker is a small script the browser keeps running beside the page. It sees the
 * page's network requests and may answer them itself. This one does a single job: keep a copy
 * of the app's own files, so the installed app opens at once and still opens with no connection.
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
