import { ApiError, api } from "../api";
import { isInstalledApp, isStayingSignedIn } from "./session";

/**
 * Notifications on this device (2.8): the browser half of Web Push.
 *
 * Turning them on takes three steps, in this order:
 *   1. the browser asks the member's permission (it must be asked in answer to a tap or click);
 *   2. the browser subscribes at its maker's push service with Brief!'s public key, and gets an
 *      endpoint (an address at Google, Mozilla, Apple or Microsoft) and a key pair of its own;
 *   3. the subscription goes to the server, which stores it with this session.
 * From then on the service worker (public/sw.js) shows what the server sends, even with Brief! closed.
 *
 * What a device can do, and why it may not:
 *   "unsupported"   no service worker or no PushManager: an old browser, a private window in some
 *                   browsers, or a page not served over https
 *   "install-first" iPhone and iPad: Safari offers notifications only to a web app added to the
 *                   Home Screen (iOS 16.4 and later), not to a tab
 *   "blocked"       the member said no, or blocked notifications in the browser settings; only
 *                   they can undo that, in the settings
 *   "needs-stay"    signed in without "Stay signed in on this device": such a sign-in ends after 12
 *                   hours or when the tab closes, so it is not offered notifications (a tap would open
 *                   Brief! signed out, and a shared computer would keep receiving them)
 *   "off" / "on"
 */
export type PushState = "unsupported" | "install-first" | "blocked" | "needs-stay" | "off" | "on";

/** True on an iPhone or iPad, including an iPad that presents itself as a Mac. */
export function isAppleMobile(): boolean {
  const ua = window.navigator.userAgent;
  return /iPhone|iPad|iPod/.test(ua) || (ua.includes("Macintosh") && window.navigator.maxTouchPoints > 1);
}

function browserCanPush(): boolean {
  return "serviceWorker" in navigator && "PushManager" in window && "Notification" in window && window.isSecureContext;
}

/** The service worker's registration, or null if there is none (development, or it failed to install). */
async function registration(): Promise<ServiceWorkerRegistration | null> {
  if (!("serviceWorker" in navigator)) return null;
  const found = await navigator.serviceWorker.getRegistration();
  if (!found) return null;
  return navigator.serviceWorker.ready;
}

/** This browser's subscription, if it has one. */
async function browserSubscription(): Promise<PushSubscription | null> {
  const reg = await registration();
  return reg ? reg.pushManager.getSubscription() : null;
}

/** Where this device stands, asking the server whether it has the subscription for this session. */
export async function pushState(token: string): Promise<PushState> {
  if (!browserCanPush()) return isAppleMobile() && !isInstalledApp() ? "install-first" : "unsupported";
  if (Notification.permission === "denied") return "blocked";
  if (!isStayingSignedIn()) return "needs-stay";
  const subscription = await browserSubscription();
  if (!subscription || Notification.permission !== "granted") return "off";
  try {
    return (await api.pushCheck(token, subscription.endpoint)) ? "on" : "off";
  } catch {
    return "off";
  }
}

/** The server key in the form PushManager.subscribe wants: bytes, from base64url. */
function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const base64 = base64url.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (base64url.length % 4)) % 4);
  const text = window.atob(base64);
  const bytes = new Uint8Array(new ArrayBuffer(text.length));
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
  return bytes;
}

function sameKey(a: ArrayBuffer | null, b: Uint8Array): boolean {
  if (!a || a.byteLength !== b.length) return false;
  const view = new Uint8Array(a);
  return view.every((byte, i) => byte === b[i]);
}

/**
 * Turns notifications on for this device. Must be called from a tap or click (step 1 above).
 * Resolves to the new state; throws ApiError(409) for a session from before 2.8 (sign in again).
 */
export async function turnOnPush(token: string): Promise<PushState> {
  if (!browserCanPush()) return isAppleMobile() && !isInstalledApp() ? "install-first" : "unsupported";
  const permission = await Notification.requestPermission();
  if (permission === "denied") return "blocked";
  if (permission !== "granted") return "off"; // the question was dismissed: ask again next time
  const reg = await registration();
  if (!reg) return "unsupported";

  const key = keyBytes(await api.pushKey());
  let subscription = await reg.pushManager.getSubscription();
  // A subscription made with another key (the server's keys were replaced) cannot receive anything now.
  if (subscription && !sameKey(subscription.options.applicationServerKey, key)) {
    await subscription.unsubscribe();
    subscription = null;
  }
  subscription ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: key });
  const json = subscription.toJSON();
  if (!json.endpoint || !json.keys?.p256dh || !json.keys?.auth) return "unsupported";
  await api.pushSubscribe(token, { endpoint: json.endpoint, keys: { p256dh: json.keys.p256dh, auth: json.keys.auth } });
  return "on";
}

/** Turns notifications off for this device: the server forgets it, and the browser drops the subscription. */
export async function turnOffPush(token: string): Promise<void> {
  const subscription = await browserSubscription();
  if (!subscription) return;
  try {
    await api.pushUnsubscribe(token, subscription.endpoint);
  } catch (problem) {
    if (problem instanceof ApiError && problem.status === 401) throw problem;
    /* the server forgets it anyway when the session ends */
  }
  await subscription.unsubscribe();
}

/**
 * On signing out: the browser drops its subscription too, so that the next person to sign in on
 * this device does not inherit it. (The server already deleted it with the session.)
 */
export async function forgetPushOnThisDevice(): Promise<void> {
  try {
    const subscription = await browserSubscription();
    await subscription?.unsubscribe();
  } catch {
    /* nothing to forget */
  }
}
