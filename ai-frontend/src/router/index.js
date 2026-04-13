import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Register from '../views/Register.vue'
import Chat from '../views/Chat.vue'
import Inventory from '../views/Inventory.vue'
import Workflow from '../views/Workflow.vue'
import DocumentProcess from '../views/DocumentProcess.vue'
import Analysis from '../views/Analysis.vue'
import { clearAuthSession, isTokenValid } from '../utils/auth'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: Login },
  { path: '/register', component: Register },
  { path: '/chat', component: Chat, meta: { requiresAuth: true } },
  { path: '/analysis', component: Analysis, meta: { requiresAuth: true } },
  { path: '/inventory', component: Inventory, meta: { requiresAuth: true } },
  { path: '/workflow', component: Workflow, meta: { requiresAuth: true } },
  { path: '/document-process', component: DocumentProcess, meta: { requiresAuth: true } }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  if (to.meta?.requiresAuth && !isTokenValid()) {
    clearAuthSession()
    next('/login')
    return
  }
  next()
})

export default router
