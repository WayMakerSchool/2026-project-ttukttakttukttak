import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import fs from "node:fs";
import path from "node:path";

// mkcert-issued cert covering localhost/127.0.0.1/LAN IP — lets a phone on
// the same WiFi load this over https:// instead of http://. Camera/mic
// access (getUserMedia) is blocked by browsers on any non-localhost origin
// served over plain http, which was silently breaking "내 카메라" mode for
// anyone opening the app via the LAN IP (e.g. from a phone) rather than
// localhost. Regenerate with:
//   mkcert -key-file .certs/key.pem -cert-file .certs/cert.pem \
//     localhost 127.0.0.1 <LAN IP> ::1
const certDir = path.resolve(__dirname, ".certs");
const httpsConfig =
  fs.existsSync(path.join(certDir, "key.pem")) &&
  fs.existsSync(path.join(certDir, "cert.pem"))
    ? {
        key: fs.readFileSync(path.join(certDir, "key.pem")),
        cert: fs.readFileSync(path.join(certDir, "cert.pem")),
      }
    : undefined;

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
    https: httpsConfig,
    proxy: {
      "/upload": "http://localhost:8000",
      "/books": "http://localhost:8000",
      "/health": "http://localhost:8000",
      "/me": "http://localhost:8000",
      "/covers": "http://localhost:8000",
      "/dict": "http://localhost:8000",
      "/camera": "http://localhost:8000",
      "/ws": { target: "ws://localhost:8000", ws: true },
    },
  },
});
