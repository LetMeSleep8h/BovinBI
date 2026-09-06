import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/Login.vue') },
    {
      path: '/',
      component: () => import('@/views/Layout.vue'),
      redirect: '/chat',
      children: [
        { path: 'chat', component: () => import('@/views/Chat.vue') },
        { path: 'datasets', component: () => import('@/views/Datasets.vue') },
        { path: 'history', component: () => import('@/views/History.vue') }
      ]
    }
  ]
})

router.beforeEach((to) => {
  const token = localStorage.getItem('bovin_token')
  if (to.path !== '/login' && !token) return '/login'
  if (to.path === '/login' && token) return '/chat'
  return true
})

export default router
