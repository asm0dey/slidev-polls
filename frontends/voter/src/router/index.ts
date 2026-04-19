import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import LandingPage from "../pages/LandingPage.vue";
import PollView from "../pages/PollView.vue";

// The server's SpaForwardingConfig forwards / and /{slug} to this SPA; the router decides
// locally between the landing page and the poll view. The slug regex mirrors polls.slug's
// CHECK constraint so a URL that could never be a real poll (upper-case, hyphen-edges) doesn't
// match here — the server already rejected it before we ever loaded.
const routes: RouteRecordRaw[] = [
  { path: "/", name: "landing", component: LandingPage },
  {
    path: "/:slug([a-z0-9]+(-[a-z0-9]+)*)",
    name: "poll",
    component: PollView,
    props: true
  }
];

export const router = createRouter({
  history: createWebHistory("/"),
  routes
});
