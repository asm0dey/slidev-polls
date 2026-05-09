import { createApp } from "vue";
import "@fontsource/inter/400.css";
import "@fontsource/inter/500.css";
import "@fontsource/inter/600.css";
import "@fontsource/inter/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "@slidev-polls/shared/tokens.css";
import App from "./App.vue";
import { router } from "./router";
import { useTheme } from "@slidev-polls/shared/ui";

useTheme(); // initialize theme on boot

const app = createApp(App);
app.use(router);
app.mount("#app");
