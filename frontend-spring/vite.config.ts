import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// The Java API listens on 8080. During development the Vite dev server (5173)
// forwards API calls there, so the browser only ever talks to one origin.
const API = process.env.BRIEF_API ?? "http://localhost:8080";
// A key that starts with "^" is a regular expression: "^/me$" is the one address /me, and not
// everything that begins with those three characters.
const apiPaths = ["/register", "/login", "/logout", "/messages", "/accounts", "/health", "^/me$", "/email", "/password", "/invitations"];

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: Object.fromEntries(apiPaths.map((path) => [path, { target: API, changeOrigin: true }])),
  },
  build: {
    // The production build goes into the Java project, so one jar serves both API and web app.
    outDir: "../backend/src/main/resources/public",
    emptyOutDir: true,
  },
});
