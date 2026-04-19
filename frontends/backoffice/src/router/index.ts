import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import LoginPage from "../pages/LoginPage.vue";
import PollListPage from "../pages/PollListPage.vue";
import PollEditorPage from "../pages/PollEditorPage.vue";

const routes: RouteRecordRaw[] = [
  { path: "/", redirect: "/polls" },
  { path: "/login", name: "login", component: LoginPage, meta: { public: true } },
  { path: "/polls", name: "polls", component: PollListPage },
  { path: "/polls/new", name: "poll-new", component: PollEditorPage, props: { mode: "create" } },
  {
    path: "/polls/:pollId",
    name: "poll-edit",
    component: PollEditorPage,
    props: (route) => ({ mode: "edit", pollId: route.params.pollId })
  }
];

export const router = createRouter({
  history: createWebHistory("/admin/"),
  routes
});
