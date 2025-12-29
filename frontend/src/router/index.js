import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import SearchResultsView from '@/views/SearchResultsView.vue'
import AdminLoginView from '@/views/AdminLoginView.vue'
import AdminLayout from '@/layouts/AdminLayout.vue'
import AdminView from '@/views/AdminView.vue'
import AdminMusicView from '@/views/AdminMusicView.vue'
import AdminUsersView from '@/views/AdminUsersView.vue'
import AdminSettingsView from '@/views/AdminSettingsView.vue'

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
      path: '/admin/login',
      name: 'admin-login',
      component: AdminLoginView
    },
    {
      path: '/admin',
      component: AdminLayout,
      beforeEnter: (to, from, next) => {
        if (isAdminLoggedIn()) {
          next(); // 如果已登录，允许访问
        } else {
          next('/admin/login'); // 如果未登录，重定向到登录页面
        }
      },
      children: [
        {
          path: '', // 默认子路由，对应 /admin
          name: 'admin',
          component: AdminView
        },
        {
          path: 'music', // 对应 /admin/music
          name: 'admin-music',
          component: AdminMusicView
        },
        {
          path: 'users', // 对应 /admin/users
          name: 'admin-users',
          component: AdminUsersView
        },
        {
          path: 'settings', // 对应 /admin/settings
          name: 'admin-settings',
          component: AdminSettingsView
        }
      ]
    }
  ]
})

export default router