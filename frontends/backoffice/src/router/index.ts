import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import LoginPage from "../pages/LoginPage.vue";
import SetupPage from "../pages/SetupPage.vue";
import PollListPage from "../pages/PollListPage.vue";
import PollEditorPage from "../pages/PollEditorPage.vue";
import DeckTokensPage from "../pages/DeckTokensPage.vue";
import UsersPage from "../pages/UsersPage.vue";
import { defaultAdminClient } from "../lib/admin-api";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/polls" },
  { path: "/setup", name: "setup", component: SetupPage, meta: { public: true } },
  { path: "/login", name: "login", component: LoginPage, meta: { public: true } },
  { path: "/polls", name: "polls", component: PollListPage },
  { path: "/polls/new", name: "poll-new", component: PollEditorPage, props: { mode: "create" } },
  {
    path: "/polls/:pollId",
    name: "poll-edit",
    component: PollEditorPage,
    props: (route) => ({ mode: "edit", pollId: route.params.pollId })
  },
  {
    path: "/polls/:pollId/deck-tokens",
    name: "deck-tokens",
    component: DeckTokensPage,
    props: (route) => ({ pollId: route.params.pollId })
  },
  { path: "/users", name: "users", component: UsersPage }
];

export const router = createRouter({
  history: createWebHistory("/admin/"),
  routes
});

let setupChecked = false;

router.beforeEach(async (to) => {
  // Run the setup-required check exactly once on first navigation. After setup is complete
  // (or once we've confirmed it's not required), subsequent navigations skip the network call.
  if (!setupChecked) {
    try {
      const status = await defaultAdminClient.getSetupStatus();
      setupChecked = true;
      if (status.setupRequired && to.name !== "setup") {
        return { name: "setup" };
      }
      if (!status.setupRequired && to.name === "setup") {
        return { name: "polls" };
      }
    } catch {
      // Network error: let the request proceed; the next protected request will surface auth errors.
      setupChecked = true;
    }
  }
});
