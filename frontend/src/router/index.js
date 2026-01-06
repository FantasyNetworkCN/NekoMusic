import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '@/views/HomeView.vue'
import SearchResultsView from '@/views/SearchResultsView.vue'
import PlayerView from '@/views/PlayerView.vue'
import UserLoginView from '@/views/UserLoginView.vue'
import UserRegisterView from '@/views/UserRegisterView.vue'
import UserFavoritesView from '@/views/UserFavoritesView.vue'
import AdminLoginView from '@/views/admin/AdminLoginView.vue'
import AdminView from '@/views/admin/AdminView.vue'
import AdminMusicView from '@/views/admin/AdminMusicView.vue'
import AdminUsersView from '@/views/admin/AdminUsersView.vue'
import AdminSettingsView from '@/views/admin/AdminSettingsView.vue'

// 检查管理员是否已登录
function isAdminLoggedIn() {
  return localStorage.getItem('isAdminLoggedIn') === 'true';
}

// 管理员路由守卫
const adminGuard = (to, from, next) => {
  if (to.path.startsWith('/admin') && to.path !== '/admin/login') {
    if (isAdminLoggedIn()) {
      next(); // 如果已登录，允许访问
    } else {
      next('/admin/login'); // 如果未登录，重定向到登录页面
    }
  } else {
    next(); // 其他路由正常访问
  }
};

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
      path: '/login',
      name: 'login',
      component: UserLoginView
    },
    {
      path: '/register',
      name: 'register',
      component: UserRegisterView
    },
    {
      path: '/favorites',
      name: 'favorites',
      component: UserFavoritesView
    },
    {
      path: '/admin/login',
      name: 'admin-login',
      component: AdminLoginView
    },
    {
      path: '/admin',
      name: 'admin',
      component: AdminView,
      beforeEnter: adminGuard
    },
    {
      path: '/admin/music',
      name: 'admin-music',
      component: AdminMusicView,
      beforeEnter: adminGuard
    },
    {
      path: '/admin/users',
      name: 'admin-users',
      component: AdminUsersView,
      beforeEnter: adminGuard
    },
    {
      path: '/detail/:id',
      name: 'detail',
      component: PlayerView,
      props: true
    }
  ]
})

export default router