import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import SearchResultsView from '@/views/SearchResultsView.vue'
import AdminView from '@/views/AdminView.vue'
import AdminLoginView from '@/views/AdminLoginView.vue'

// 检查管理员是否已登录
function isAdminLoggedIn() {
  return localStorage.getItem('isAdminLoggedIn') === 'true';
}

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      name: 'home',
      component: HomeView
    },
    {
      path: '/search',
      name: 'search',
      component: SearchResultsView,
      props: true
    },
    {
      path: '/search/:query',
      name: 'search-query',
      component: SearchResultsView,
      props: true
    },
    {
      path: '/admin',
      name: 'admin',
      component: AdminView,
      beforeEnter: (to, from, next) => {
        if (isAdminLoggedIn()) {
          next(); // 如果已登录，允许访问
        } else {
          next('/admin/login'); // 如果未登录，重定向到登录页面
        }
      }
    },
    {
      path: '/admin/login',
      name: 'admin-login',
      component: AdminLoginView
    }
  ]
})

export default router