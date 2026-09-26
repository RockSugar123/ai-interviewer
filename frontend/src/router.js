import { createRouter, createWebHashHistory } from 'vue-router'
import LoginView from './views/LoginView.vue'
import ChatView from './views/ChatView.vue'
import ReportView from './views/ReportView.vue'

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', component: LoginView },
    { path: '/', component: ChatView },
    { path: '/report/:id', component: ReportView }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('token')) return '/login'
  if (to.path === '/login' && localStorage.getItem('token')) return '/'
})

export default router
