import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    host: true,
    port: 5173,
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
