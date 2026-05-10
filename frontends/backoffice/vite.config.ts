import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

export default defineConfig({
  plugins: [vue()],
  base: "/admin/",
  build: {
    sourcemap: true
  },
  server: {
    proxy: {
      "/api": "http://localhost:8080"
    }
  }
});
