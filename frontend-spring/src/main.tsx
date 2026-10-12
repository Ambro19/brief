import React from "react";
import ReactDOM from "react-dom/client";
import "@fontsource-variable/schibsted-grotesk";
import "@fontsource-variable/source-serif-4";
import "@fontsource-variable/source-serif-4/wght-italic.css";
import "./styles.css";
import App from "./App";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);

// The service worker (public/sw.js) keeps a copy of the app's own files, so the installed app
// opens at once and still opens without a connection. It is registered only in the built app:
// during development it would serve yesterday's files while you edit today's. Browsers allow
// service workers on https:// and on localhost, and nowhere else.
if (import.meta.env.PROD && "serviceWorker" in navigator) {
  window.addEventListener("load", () => {
    navigator.serviceWorker.register("/sw.js").catch(() => {
      /* without it the app works as before; it just cannot be opened offline */
    });
  });
}
