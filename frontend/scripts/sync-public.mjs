// After "vite build" has written the web app into the Javalin project, copy the same files into
// the Spring Boot project, so both backends serve an identical web app.
import { cpSync, existsSync, rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const built = resolve(here, "../../backend/src/main/resources/public");
const springProject = resolve(here, "../../backend-spring");
const target = resolve(springProject, "src/main/resources/public");

if (!existsSync(built)) {
  console.error(`Nothing to copy: ${built} does not exist. Run "vite build" first.`);
  process.exit(1);
}
if (!existsSync(springProject)) {
  console.log("backend-spring is not present; skipping the copy.");
  process.exit(0);
}
rmSync(target, { recursive: true, force: true });
cpSync(built, target, { recursive: true });
console.log(`Copied the web app to ${target}`);
