import { useEffect, useState } from "react";
import { isInstalledApp } from "../lib/session";

/**
 * The event Chrome, Edge and Android browsers send when a site can be installed as an app.
 * It is not in TypeScript's standard list of events, so its shape is declared here.
 */
interface BeforeInstallPromptEvent extends Event {
  prompt: () => Promise<void>;
  userChoice: Promise<{ outcome: "accepted" | "dismissed" }>;
}

function isIphoneOrIpad(): boolean {
  const agent = window.navigator.userAgent;
  // An iPad can report itself as a Mac; the touch screen gives it away.
  return /iPhone|iPad|iPod/.test(agent) || (agent.includes("Macintosh") && window.navigator.maxTouchPoints > 1);
}

/**
 * Offers to put Brief! on the home screen or desktop, where the browser makes that possible.
 *
 *   Chrome, Edge, Android: the browser announces "this can be installed"; a button then opens
 *                          the browser's own install dialog.
 *   iPhone and iPad:       Safari has no such dialog, so the two taps are spelled out.
 *   Already installed, or a browser that cannot install: nothing is shown.
 *
 * Installing needs https:// (or localhost). On a plain http:// address the browser never
 * makes the offer, and this component stays empty.
 */
export function InstallHint() {
  const [offer, setOffer] = useState<BeforeInstallPromptEvent | null>(null);
  const [installed, setInstalled] = useState(() => isInstalledApp());

  useEffect(() => {
    const onOffer = (event: Event) => {
      event.preventDefault(); // keep the browser's own banner away; the button below replaces it
      setOffer(event as BeforeInstallPromptEvent);
    };
    const onInstalled = () => {
      setOffer(null);
      setInstalled(true);
    };
    window.addEventListener("beforeinstallprompt", onOffer);
    window.addEventListener("appinstalled", onInstalled);
    return () => {
      window.removeEventListener("beforeinstallprompt", onOffer);
      window.removeEventListener("appinstalled", onInstalled);
    };
  }, []);

  if (installed) return null;

  if (offer) {
    return (
      <p className="install">
        <button
          type="button"
          className="link"
          onClick={() => {
            void offer.prompt();
            void offer.userChoice.finally(() => setOffer(null)); // the browser allows each offer to be used once
          }}
        >
          Install Brief! on this device
        </button>
      </p>
    );
  }

  if (isIphoneOrIpad() && window.isSecureContext) {
    return <p className="install">To install Brief! here: tap Share, then Add to Home Screen.</p>;
  }

  return null;
}
