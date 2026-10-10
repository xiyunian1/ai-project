import { createRouter, createWebHistory } from 'vue-router'
import Login from '../views/Login.vue'
import Chat from '../views/Chat.vue'
import Inventory from '../views/Inventory.vue'
import Knowledge from '../views/Knowledge.vue'
import DocumentProcess from '../views/DocumentProcess.vue'
import Analysis from '../views/Analysis.vue'
import Settings from '../views/Settings.vue'
import Account from '../views/Account.vue'
import UserManagement from '../views/UserManagement.vue'
import { clearAuthSession, isAdminRole, isTokenValid } from '../utils/auth'

const routes = [
  { path: '/', redirect: '/login' },
  { path: '/login', component: Login },
  { path: '/register', redirect: '/login' },
  { path: '/chat', component: Chat, meta: { requiresAuth: true } },
  { path: '/analysis', component: Analysis, meta: { requiresAuth: true } },
  { path: '/inventory', component: Inventory, meta: { requiresAuth: true } },
  { path: '/knowledge', component: Knowledge, meta: { requiresAuth: true } },
  { path: '/document-process', component: DocumentProcess, meta: { requiresAuth: true } },
  { path: '/settings', component: Settings, meta: { requiresAuth: true } },
  { path: '/admin/users', component: UserManagement, meta: { requiresAuth: true, requiresAdmin: true } },
  { path: '/account', component: Account, meta: { requiresAuth: true } }
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
  if (to.meta?.requiresAdmin && !isAdminRole()) {
    next('/chat')
    return
  }
  next()
})

export default router
