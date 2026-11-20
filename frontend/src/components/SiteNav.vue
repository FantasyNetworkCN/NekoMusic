<script setup>
/**
 * SiteNav —— 普通用户页面的横向导航
 * ------------------------------------------------------------
 * 桌面端位于品牌顶栏下方，移动端切换为底部导航。
 * 不占用左侧固定栏，让用户页面保持完整内容宽度。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import NIcon from '@/icons/NIcon.vue'
import { t } from '@/i18n'

const route = useRoute()

const links = computed(() => [
  { to: '/', name: 'home', icon: 'home', label: t('sidebar.discover') },
  { to: '/ranking', name: 'ranking', icon: 'chart', label: t('sidebar.ranking') },
  { to: '/latest', name: 'latest', icon: 'sparkles', label: t('sidebar.latest') },
  { to: '/playlists', name: 'playlists', icon: 'list-music', label: t('sidebar.playlists') },
  { to: '/favorites', name: 'favorites', icon: 'heart', label: t('sidebar.favorites') },
  { to: '/lxmusic', name: 'lxmusic', icon: 'file-up', label: t('sidebar.lxmusic') },
  { to: '/tools-lyr', name: 'tools-lyr', icon: 'audio-lines', label: t('sidebar.lyrics') },
  { to: '/download', name: 'download', icon: 'download', label: t('sidebar.download') },
])

const mobileLinks = computed(() => links.value.slice(0, 5))

const isActive = (item) => {
  if (item.name === 'home') return route.name === 'home'
  if (item.name === 'playlists') return ['playlists', 'playlist-detail', 'create-playlist'].includes(route.name)
  return route.name === item.name
}
</script>

<style scoped>
.site-nav {
  position: sticky;
  top: var(--app-header-h, var(--n-header-height));
  z-index: var(--n-z-sticky);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--n-space-4);
  min-height: 48px;
  padding: 0 var(--n-content-gutter);
  border-bottom: 1px solid var(--n-line-subtle);
  background: rgba(14, 17, 24, 0.9);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
}

.site-nav__group {
  display: flex;
  align-items: center;
  gap: 3px;
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.site-nav__group::-webkit-scrollbar {
  display: none;
}

.site-nav__link,
.site-nav__account {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid transparent;
  border-radius: 9px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  white-space: nowrap;
  transition: background var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease), border-color var(--n-duration-fast) var(--n-ease);
}

.site-nav__link.is-active {
  color: var(--n-text);
  border-color: var(--n-accent-line);
  background: var(--n-accent-soft);
}

.site-nav__account {
  flex: none;
  color: var(--n-text-faint);
}

@media (hover: hover) {
  .site-nav__link:hover,
  .site-nav__account:hover {
    color: var(--n-text);
    background: var(--n-surface-hover);
  }
}

.site-mobile-nav {
  display: none;
}

@media (max-width: 900px) {
  .site-nav {
    display: none;
  }

  .site-mobile-nav {
    position: fixed;
    left: 12px;
    right: 12px;
    bottom: calc(10px + var(--n-safe-bottom));
    z-index: var(--n-z-header);
    display: grid;
    grid-template-columns: repeat(5, 1fr);
    gap: 3px;
    padding: 6px;
    border: 1px solid var(--n-line-strong);
    border-radius: 17px;
    background: rgba(18, 22, 30, 0.94);
    box-shadow: var(--n-shadow-lg);
    backdrop-filter: blur(18px);
    -webkit-backdrop-filter: blur(18px);
  }

  .site-mobile-nav__link {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    min-height: 48px;
    border-radius: 12px;
    color: var(--n-text-faint);
    font-size: 0.62rem;
    font-weight: var(--n-weight-medium);
  }

  .site-mobile-nav__link.is-active {
    color: var(--n-accent-strong);
    background: var(--n-accent-soft);
  }
}
</style>
