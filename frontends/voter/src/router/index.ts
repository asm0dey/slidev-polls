import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import LandingPage from "../pages/LandingPage.vue";
import PollView from "../pages/PollView.vue";

// The server's SpaForwardingConfig forwards / and /{slug} to this SPA; the router decides
// locally between the landing page and the poll view. The slug pattern mirrors the server's
// SpaForwardingConfig forwarding regex ([a-z0-9-]{3,40}) — a single character class with a
// length bound. vue-router's path parser cannot handle nested capturing groups combined with
// `*` (it emits a malformed regex and throws at app boot, leaving #app empty — BUG-005), so
// stricter shape checks (no leading/trailing hyphen, no `--`) live server-side in
// SlugValidator where they belong. The backend returns 404 for any malformed slug that makes
// it through, and PollView renders an actionable "no such poll" message for that case.
const routes: RouteRecordRaw[] = [
  { path: "/", name: "landing", component: LandingPage },
  {
    path: "/:slug([a-z0-9-]{3,40})",
    name: "poll",
    component: PollView,
    props: true
  }
];

export const router = createRouter({
  history: createWebHistory("/"),
  routes
});
