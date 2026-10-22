import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

function apiProxy(target, rewrite) {
  return {
    target,
    rewrite,
    configure(proxy) {
      proxy.on("proxyReq", (proxyReq) => {
        const username = process.env.METADATA_API_USERNAME || "metadata-gateway";
        const password = process.env.METADATA_API_PASSWORD || "metadata-gateway";
        const token = Buffer.from(`${username}:${password}`).toString("base64");
        proxyReq.setHeader("Authorization", `Basic ${token}`);
      });
    },
  };
}

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/login/graphql": apiProxy("http://localhost:4010", (path) => path.replace(/^\/login/, "")),
      "/intake/graphql": apiProxy("http://localhost:4020", (path) => path.replace(/^\/intake/, "")),
      "/graphql": apiProxy("http://localhost:4000"),
    },
  },
});
