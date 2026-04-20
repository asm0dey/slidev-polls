import { createApp } from "vue";
import App from "./App.vue";
import { router } from "./router";
import { defaultAdminClient } from "./lib/admin-api";

defaultAdminClient.setOnUnauthorized(() => {
  if (router.currentRoute.value.name !== "login") {
    void router.replace({ name: "login" });
  }
});

const app = createApp(App);
app.use(router);
app.mount("#app");
