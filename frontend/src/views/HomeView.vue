<script setup>
/**
 * HomeView —— 首页
 * ------------------------------------------------------------
 * 排布参考主流音乐 App（ArchoeraMusic 首页范式）：
 *   页头（标题 + 问候） → Hero 横幅 → 动作卡 → 横向封面栏 ×2
 *
 * Hero 双态：
 *  - 已登录且取到每日推荐 → 展示「每日推荐」（2×2 封面拼图 + 播放推荐）
 *  - 否则 → 展示品牌文案（热门第一首 + 播放热门）
 *
 * 设计：黑偏青 + 圆角矩形；不使用侧边高亮条与区块级动画渐变。
 * 全局契约：播放经 usePlaybackBridge.playTrack / playTracks 统一驱动 GlobalPlayer。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'
import NIcon from '@/icons/NIcon.vue'
import QualityBadge from '@/components/QualityBadge.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { useToast } from '@/composables/useToast'
import { playTracks, playTrackInList, isCurrentMusic } from '@/composables/usePlaybackBridge'
import NowPlayingTag from '@/components/NowPlayingTag.vue'
import { coverSrcset } from '@/utils/coverImage'
import { getUser } from '@/utils/userStore.js'
import { t } from '@/i18n'

const toast = useToast()

const rankingList = ref([])
const latestList = ref([])
const rankingLoading = ref(true)
const latestLoading = ref(true)

const isLoggedIn = ref(false)
const nickname = ref('')

/** 每日推荐（需登录） */
const dailyList = ref([])
const dailyDate = ref('')

/** 推荐歌单：登录用户个性化，游客热门精选；每次刷新实时请求，不使用缓存 */
const recommendedPlaylists = ref([])
const playlistLoading = ref(false)
const playlistRefreshing = ref(false)
const playlistPersonalized = ref(false)

const syncLoginState = () => {
  isLoggedIn.value = !!localStorage.getItem('userToken')
  nickname.value = getUser()?.nickname || ''
}

/** 按时段问候 */
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return t('home.greetingNight')
  if (h < 12) return t('home.greetingMorning')
  if (h < 14) return t('home.greetingNoon')
  if (h < 18) return t('home.greetingAfternoon')
  return t('home.greetingEvening')
})

const hasDaily = computed(() => isLoggedIn.value && dailyList.value.length > 0)

/** Hero：优先每日推荐，否则热门第一首 */
const heroList = computed(() => (hasDaily.value ? dailyList.value : rankingList.value))
const heroFeature = computed(() => heroList.value[0] || null)
const heroBackdrop = computed(() => heroFeature.value?.coverUrl || '')
const dailyMosaic = computed(() => dailyList.value.slice(0, 4))

/** 横向封面栏取前 12 首 */
const hotList = computed(() => rankingList.value.slice(0, 12))
const latestGrid = computed(() => latestList.value.slice(0, 12))

const loadingAll = computed(
  () => (rankingLoading.value || latestLoading.value) && !rankingList.value.length && !latestList.value.length
)

const quickLinks = computed(() => {
  const links = [
    { to: '/ranking', icon: 'trophy', title: t('home.quickRanking'), desc: t('home.quickRankingDesc') },
    { to: '/latest', icon: 'sparkles', title: t('home.quickLatest'), desc: t('home.quickLatestDesc') },
    { to: '/download', icon: 'download', title: t('home.quickDownload'), desc: t('home.quickDownloadDesc') },
  ]
  links.push(
    isLoggedIn.value
      ? { to: '/upload', icon: 'upload', title: t('home.quickUpload'), desc: t('home.quickUploadDesc') }
      : { to: '/favorites', icon: 'heart', title: t('home.quickFavorites'), desc: t('home.quickFavoritesDesc') }
  )
  return links
})

const withCover = (item) => ({
  ...item,
  coverUrl: `${API_CONFIG.BASE_URL}/api/music/cover/${item.id}`,
})

const fetchRanking = async () => {
  rankingLoading.value = true
  try {
    const res = await fetch(`${API_CONFIG.BASE_URL}/api/music/ranking`)
    const data = await res.json()
    if (data.success && data.data) rankingList.value = data.data.map(withCover)
    else console.error('获取排行榜失败:', data.message)
  } catch (error) {
    console.error('排行榜请求失败:', error)
    toast.error(t('home.loadRankingFailed'))
  } finally {
    rankingLoading.value = false
  }
}

const fetchLatest = async () => {
  latestLoading.value = true
  try {
    const res = await fetch(`${API_CONFIG.BASE_URL}/api/music/latest`)
    const data = await res.json()
    if (data.success && data.data) latestList.value = data.data.map(withCover)
    else console.error('获取最新音乐失败:', data.message)
  } catch (error) {
    console.error('最新音乐请求失败:', error)
    toast.error(t('home.loadLatestFailed'))
  } finally {
    latestLoading.value = false
  }
}

/**
 * 每日推荐：失败/未登录时静默回退到品牌 Hero，不打扰用户。
 * 契约：Authorization 传裸 userToken（与其它用户接口一致）。
 */
const fetchDaily = async () => {
  const token = localStorage.getItem('userToken')
  if (!token) {
    dailyList.value = []
    return
  }
  try {
    const res = await fetch(`${API_CONFIG.BASE_URL}/api/user/recommendations/daily`, {
      headers: { Authorization: token },
    })
    if (!res.ok) {
      dailyList.value = []
      return
    }
    const data = await res.json()
    if (data.success && Array.isArray(data.data)) {
      dailyList.value = data.data.map((it) => ({
        ...it,
        id: it.musicId,
        coverUrl: `${API_CONFIG.BASE_URL}/api/music/cover/${it.musicId}`,
      }))
      dailyDate.value = data.date || ''
    } else {
      dailyList.value = []
    }
  } catch (error) {
    console.error('每日推荐请求失败:', error)
    dailyList.value = []
  }
}

const resolveCoverPath = (path) => {
  if (!path) return `${API_CONFIG.BASE_URL}/api/music/cover/0`
  if (/^https?:\/\//i.test(path)) return path
  return `${API_CONFIG.BASE_URL}${path.startsWith('/') ? path : `/${path}`}`
}

const normalizeRecommendedPlaylist = (playlist) => {
  const covers = Array.isArray(playlist.coverUrls) && playlist.coverUrls.length
    ? playlist.coverUrls.map(resolveCoverPath)
    : [resolveCoverPath(playlist.firstMusicCover)]
  return {
    ...playlist,
    coverUrls: covers,
  }
}

const fetchRecommendedPlaylists = async (refresh = false) => {
  if (playlistLoading.value) return
  playlistLoading.value = true
  playlistRefreshing.value = refresh
  try {
    const token = localStorage.getItem('userToken')
    const query = refresh ? `?refresh=true&t=${Date.now()}` : `?t=${Date.now()}`
    const doFetch = (withToken) => fetch(`${API_CONFIG.BASE_URL}/api/playlists/recommended${query}`, {
      cache: 'no-store',
      headers: withToken ? { Authorization: withToken } : {},
    })
    let res = await doFetch(token)
    if (res.status === 401 && token) {
      // Token 过期时仍允许游客推荐，不让过期的本地登录态挡住首页内容。
      res = await doFetch('')
    }
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    const data = await res.json()
    if (!data.success || !Array.isArray(data.data)) {
      throw new Error(data.message || 'invalid recommendation payload')
    }
    recommendedPlaylists.value = data.data.map(normalizeRecommendedPlaylist)
    playlistPersonalized.value = data.personalized === true
  } catch (error) {
    console.error('推荐歌单请求失败:', error)
    if (refresh) toast.error(t('home.playlistLoadFailed'))
  } finally {
    playlistLoading.value = false
    playlistRefreshing.value = false
  }
}

const handleImageError = (event) => {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/0`
}

const toTrack = (m) => ({
  id: m.id,
  title: m.title,
  artist: m.artist,
  album: m.album,
  duration: m.duration,
})

/** 点单曲：以所在列表为播放队列，保证能接着听「下一首」 */
const playMusic = (m, list) => {
  playTrackInList(toTrack(m), (Array.isArray(list) ? list : []).map(toTrack))
  toast.success(t('home.playStarted', { title: m.title }))
}

const playList = (list, label) => {
  if (!list.length) return
  playTracks(list.map(toTrack), 0)
  toast.success(t('home.playListStarted', { label: label || '', count: list.length }))
}

const playHero = () => {
  if (heroFeature.value) playMusic(heroFeature.value, heroList.value)
}

const playHeroList = () => {
  playList(heroList.value, hasDaily.value ? t('home.dailyBadge') : t('home.hot'))
}

function onStorage() {
  const wasLoggedIn = isLoggedIn.value
  syncLoginState()
  if (isLoggedIn.value && !wasLoggedIn) fetchDaily()
  if (!isLoggedIn.value) dailyList.value = []
  fetchRecommendedPlaylists(false)
}

onMounted(() => {
  syncLoginState()
  window.addEventListener('storage', onStorage)
  fetchRanking()
  fetchLatest()
  fetchDaily()
  fetchRecommendedPlaylists(false)
})

onUnmounted(() => {
  window.removeEventListener('storage', onStorage)
})
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="wide" class="home-page">
    <header class="home-head">
      <div>
        <p class="home-head__kicker"><span /> {{ $t('sidebar.discover') }}</p>
        <h1 class="home-head__title">{{ $t('home.title') }}</h1>
        <p class="home-head__greeting">
          {{ isLoggedIn && nickname ? $t('home.welcome', { greeting, nickname }) : $t('home.welcomeAnon', { greeting }) }}
        </p>
      </div>
      <div class="home-head__meta">
        <span class="home-head__meta-label">{{ hasDaily ? $t('home.dailyBadge') : $t('home.hotTopBadge') }}</span>
        <strong>{{ heroFeature?.title || $t('common.loading') }}</strong>
        <small>{{ heroFeature?.artist || 'Neko Music' }}</small>
      </div>
    </header>

    <div class="home-layout">
      <div class="home-main">
        <section class="feature-panel">
          <div
            v-if="heroBackdrop"
            class="feature-panel__backdrop"
            :style="{ backgroundImage: `url(${heroBackdrop})` }"
            aria-hidden="true"
          />
          <div class="feature-panel__wash" aria-hidden="true" />
          <div class="feature-panel__copy">
            <span class="feature-panel__eyebrow">
              <NIcon :name="hasDaily ? 'sparkles' : 'flame'" :size="14" />
              {{ hasDaily ? $t('home.heroBadgeDaily') : $t('home.heroBadgeBrand') }}
            </span>
            <h2>{{ hasDaily ? $t('home.heroTitleDaily') : $t('home.heroTitleBrand') }}</h2>
            <p v-if="hasDaily">
              {{ $t('home.heroDescDailyCount', { count: dailyList.length }) }}
            </p>
            <p v-else>
              {{ $t('home.heroDescBrandLead') }} <strong>{{ $t('home.heroDescBrandSources') }}</strong>{{ $t('home.heroDescBrandTail') }}
            </p>
            <div class="feature-panel__actions">
              <NButton
                v-if="hasDaily || rankingList.length"
                variant="primary"
                icon="play"
                @click="playHeroList"
              >
                {{ hasDaily ? $t('home.playRecommended') : $t('home.playHot') }}
              </NButton>
              <NButton variant="ghost" icon="download" to="/download">
                {{ $t('home.downloadClient') }}
              </NButton>
            </div>
          </div>
          <button
            v-if="heroFeature"
            type="button"
            class="feature-panel__art"
            :aria-label="$t('home.heroPlayAria')"
            @click="playHero"
          >
            <span v-if="hasDaily" class="feature-panel__mosaic">
              <img
                v-for="m in dailyMosaic"
                :key="m.id"
                :src="m.coverUrl"
                :alt="m.title"
                loading="lazy"
                decoding="async"
                @error="handleImageError"
              />
            </span>
            <img
              v-else
              :src="heroFeature.coverUrl"
              :srcset="coverSrcset(heroFeature.coverUrl)"
              :alt="heroFeature.title"
              loading="eager"
              decoding="async"
              @error="handleImageError"
            />
            <span class="feature-panel__art-play"><NIcon name="play" :size="20" /></span>
          </button>
        </section>

        <section class="section-block" :aria-label="$t('home.playlistTitle')">
          <header class="section-head">
            <div>
              <p class="section-head__eyebrow">{{ $t('home.playlistPersonalizedBadge') }}</p>
              <h2>{{ $t('home.playlistTitle') }}</h2>
              <p>{{ playlistPersonalized ? $t('home.playlistSubPersonalized') : $t('home.playlistSubGuest') }}</p>
            </div>
            <NButton
              variant="ghost"
              size="sm"
              icon="refresh"
              :loading="playlistRefreshing"
              :disabled="playlistLoading"
              :title="$t('home.playlistRefresh')"
              @click="fetchRecommendedPlaylists(true)"
            />
          </header>

          <div v-if="playlistLoading && !recommendedPlaylists.length" class="playlist-state">
            <NSpinner :size="24" />
            <span>{{ $t('home.playlistLoading') }}</span>
          </div>
          <div v-else-if="recommendedPlaylists.length" class="playlist-grid">
            <RouterLink
              v-for="playlist in recommendedPlaylists"
              :key="playlist.id"
              :to="`/playlist/${playlist.id}`"
              class="playlist-card"
            >
              <div class="playlist-card__art">
                <div class="playlist-card__mosaic" :class="{ 'playlist-card__mosaic--single': playlist.coverUrls.length === 1 }">
                  <img
                    v-for="(cover, coverIndex) in playlist.coverUrls.slice(0, 4)"
                    :key="`${playlist.id}-${coverIndex}`"
                    :src="cover"
                    :alt="coverIndex === 0 ? playlist.name : ''"
                    loading="lazy"
                    decoding="async"
                    @error="handleImageError"
                  />
                </div>
                <span class="playlist-card__badge">
                  <NIcon :name="playlist.source === 'personalized' ? 'sparkles' : 'trending-up'" :size="11" />
                  {{ playlist.source === 'personalized' ? $t('home.playlistPersonalizedBadge') : $t('home.playlistPopularBadge') }}
                </span>
              </div>
              <h3>{{ playlist.name }}</h3>
              <p>{{ playlist.reason || $t('home.playlistUnknownCreator') }}</p>
            </RouterLink>
          </div>
          <p v-else class="playlist-state">{{ $t('home.playlistEmpty') }}</p>
        </section>

        <section v-if="latestGrid.length" class="section-block">
          <header class="section-head">
            <div>
              <p class="section-head__eyebrow">{{ $t('home.latestTitle') }}</p>
              <h2>{{ $t('home.latestSub') }}</h2>
            </div>
            <NButton variant="ghost" size="sm" icon-after="chevron-right" to="/latest">{{ $t('common.more') }}</NButton>
          </header>
          <div class="cover-rail">
            <article v-for="m in latestGrid.slice(0, 6)" :key="m.id" class="cover-card">
              <div class="cover-card__art">
                <img :src="m.coverUrl" :srcset="coverSrcset(m.coverUrl)" :alt="m.title" loading="lazy" @error="handleImageError" />
                <button
                  type="button"
                  class="cover-card__play"
                  :aria-label="$t('home.playAria', { title: m.title })"
                  @click="playMusic(m, latestGrid)"
                >
                  <NIcon :name="isCurrentMusic(m.id) ? 'volume-2' : 'play'" :size="16" />
                </button>
              </div>
              <h3>{{ m.title }}</h3>
              <p>{{ m.artist }}</p>
            </article>
          </div>
        </section>
      </div>

      <aside class="home-side">
        <section class="side-panel">
          <header class="side-panel__head">
            <div>
              <p class="section-head__eyebrow">{{ $t('home.hotTitle') }}</p>
              <h2>{{ $t('home.hotSub') }}</h2>
            </div>
            <NButton variant="ghost" size="sm" icon-after="chevron-right" to="/ranking">{{ $t('common.more') }}</NButton>
          </header>
          <div v-if="loadingAll" class="side-panel__loading"><NSpinner :size="22" /></div>
          <div v-else class="rank-list">
            <button
              v-for="(m, i) in hotList.slice(0, 7)"
              :key="m.id"
              type="button"
              class="rank-row"
              @click="playMusic(m, hotList)"
            >
              <span class="rank-row__number">{{ String(i + 1).padStart(2, '0') }}</span>
              <img :src="m.coverUrl" :alt="m.title" loading="lazy" @error="handleImageError" />
              <span class="rank-row__copy">
                <strong>{{ m.title }}</strong>
                <small>{{ m.artist }}</small>
              </span>
              <NIcon :name="isCurrentMusic(m.id) ? 'volume-2' : 'play'" :size="15" class="rank-row__icon" />
            </button>
          </div>
        </section>

        <section class="import-panel">
          <div class="import-panel__icon"><NIcon name="file-up" :size="22" /></div>
          <p class="section-head__eyebrow">{{ $t('home.lxmcBadge') }}</p>
          <h2>{{ $t('home.lxmcTitle') }}</h2>
          <p>{{ $t('home.lxmcDesc') }}</p>
          <NButton variant="secondary" block icon="file-up" to="/playlists?import=lxmc">
            {{ $t('home.lxmcImport') }}
          </NButton>
        </section>

        <section class="quick-panel" :aria-label="$t('home.quickAria')">
          <header class="side-panel__head">
            <div>
              <p class="section-head__eyebrow">{{ $t('home.quickAria') }}</p>
              <h2>{{ $t('sidebar.tools') }}</h2>
            </div>
          </header>
          <RouterLink v-for="q in quickLinks" :key="q.to" :to="q.to" class="quick-row">
            <span><NIcon :name="q.icon" :size="17" /></span>
            <strong>{{ q.title }}</strong>
            <NIcon name="chevron-right" :size="14" />
          </RouterLink>
        </section>
      </aside>
    </div>
  </PageShell>
</template>

<style scoped>
/* ==================== 页头 ==================== */
.home-head {
  margin-bottom: var(--n-space-6);
}

.home-head__title {
  font-size: clamp(1.5rem, 3vw, 1.9rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.03em;
}

.home-head__greeting {
  margin-top: var(--n-space-1);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

/* ==================== Hero 横幅 ==================== */
.hero {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: clamp(20px, 4vw, 44px);
  min-height: 208px;
  padding: clamp(20px, 3.5vw, 32px);
  margin-bottom: clamp(24px, 3.5vw, 36px);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  overflow: hidden;
  background: var(--n-surface);
}

/* 模糊封面底 */
.hero__bg {
  position: absolute;
  inset: -20%;
  background-size: cover;
  background-position: center;
  filter: blur(64px) saturate(1.3);
  opacity: 0.28;
  transform: scale(1.1);
}

.hero__scrim {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(100deg, rgba(4, 9, 11, 0.94) 22%, rgba(4, 9, 11, 0.6) 62%, rgba(4, 9, 11, 0.32) 100%);
}

.hero__copy {
  position: relative;
  z-index: var(--n-z-content);
  min-width: 0;
}

.hero__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: var(--n-space-2);
  padding: 5px 11px;
  border-radius: var(--n-radius-xs);
  background: var(--n-accent-soft);
  border: 1px solid var(--n-accent-line);
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
  letter-spacing: 0.02em;
}

.hero__title {
  margin: var(--n-space-4) 0 var(--n-space-2);
  font-size: clamp(1.7rem, 4vw, 2.6rem);
  font-weight: var(--n-weight-bold);
  line-height: 1.1;
  letter-spacing: -0.03em;
  background: var(--n-gradient-text);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.hero__lede {
  max-width: 42ch;
  color: var(--n-text-muted);
  font-size: var(--n-text-base);
  line-height: var(--n-leading-normal);
}

.hero__lede strong {
  color: var(--n-text);
  font-weight: var(--n-weight-semibold);
}

.hero__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--n-space-3);
  margin-top: var(--n-space-5);
}

/* ==================== Hero 右侧视觉 ==================== */
.hero__feature {
  position: relative;
  z-index: var(--n-z-content);
  width: clamp(150px, 22vw, 200px);
  aspect-ratio: 1;
  border-radius: var(--n-radius-lg);
  overflow: hidden;
  border: 1px solid var(--n-line-strong);
  background: var(--n-surface-soft);
  box-shadow: var(--n-shadow-lg);
  cursor: pointer;
  padding: 0;
}

.hero__feature > img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

/* 每日推荐：2×2 拼图 */
.hero__mosaic {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 2px;
  width: 100%;
  height: 100%;
}

.hero__mosaic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

@media (hover: hover) {
  .hero__feature:hover > img,
  .hero__feature:hover .hero__mosaic img {
    transform: scale(1.05);
  }
}

.hero__badge {
  position: absolute;
  top: var(--n-space-3);
  left: var(--n-space-3);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: var(--n-radius-xs);
  background: rgba(4, 9, 11, 0.72);
  backdrop-filter: var(--n-blur-sm);
  -webkit-backdrop-filter: var(--n-blur-sm);
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.hero__play {
  position: absolute;
  right: var(--n-space-3);
  top: var(--n-space-3);
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: var(--n-radius-control);
  background: var(--n-accent);
  color: var(--n-text-inverse);
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.4);
  transition: transform var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .hero__feature:hover .hero__play {
    transform: scale(1.08);
    background: var(--n-accent-strong);
  }
}

.hero__feature-meta {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 26px var(--n-space-3) var(--n-space-3);
  text-align: left;
  background: linear-gradient(to top, rgba(4, 9, 11, 0.9), transparent);
}

.hero__feature-title {
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.hero__feature-artist {
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ==================== 落雪 LXMC 入口 ==================== */
.lxmc-entry {
  position: relative;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--n-space-5);
  margin-bottom: clamp(24px, 3.5vw, 36px);
  padding: clamp(18px, 2.8vw, 26px);
  border: 1px solid rgba(95, 208, 224, 0.32);
  border-radius: var(--n-radius-lg);
  overflow: hidden;
  background:
    radial-gradient(circle at 86% 10%, rgba(95, 208, 224, 0.18), transparent 34%),
    linear-gradient(110deg, rgba(95, 208, 224, 0.14), rgba(10, 20, 25, 0.9));
  box-shadow: 0 16px 42px rgba(0, 0, 0, 0.22);
}

.lxmc-entry::before {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.035), transparent);
  transform: translateX(-100%);
  pointer-events: none;
}

.lxmc-entry__icon {
  position: relative;
  display: grid;
  place-items: center;
  width: 76px;
  height: 76px;
  border: 1px solid var(--n-accent-line);
  border-radius: 20px;
  background: rgba(4, 12, 16, 0.52);
  color: var(--n-accent-strong);
}

.lxmc-entry__icon span {
  position: absolute;
  right: -8px;
  bottom: -7px;
  padding: 2px 7px;
  border-radius: var(--n-radius-xs);
  background: var(--n-accent);
  color: var(--n-text-inverse);
  font-size: 10px;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.03em;
}

.lxmc-entry__copy {
  min-width: 0;
}

.lxmc-entry__badge {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.lxmc-entry h2 {
  margin: var(--n-space-1) 0 2px;
  font-size: clamp(1.05rem, 2vw, 1.35rem);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.02em;
}

.lxmc-entry p {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.lxmc-entry__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: flex-end;
  gap: var(--n-space-2);
}

/* ==================== 动作卡 ==================== */
.quick {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--n-space-4);
  margin-bottom: clamp(28px, 4vw, 44px);
}

.quick__card {
  display: flex;
  align-items: center;
  gap: var(--n-space-4);
  text-decoration: none;
  color: inherit;
}

.quick__icon {
  display: grid;
  place-items: center;
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: var(--n-radius-sm);
  background: var(--n-accent-soft);
  color: var(--n-accent-strong);
}

.quick__body {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.quick__title {
  font-weight: var(--n-weight-semibold);
  color: var(--n-text);
}

.quick__desc {
  font-size: var(--n-text-sm);
  color: var(--n-text-muted);
}

.quick__arrow {
  flex: none;
  color: var(--n-text-faint);
  transition: transform var(--n-duration-fast) var(--n-ease), color var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .quick__card:hover .quick__arrow {
    transform: translateX(3px);
    color: var(--n-accent);
  }
}

/* ==================== 推荐歌单 ==================== */
.playlist-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--n-space-4);
}

.playlist-card {
  min-width: 0;
  overflow: hidden;
  text-decoration: none;
  color: inherit;
}

.playlist-card__art {
  position: relative;
  aspect-ratio: 1;
  overflow: hidden;
  background: var(--n-surface-soft);
}

.playlist-card__mosaic {
  display: grid;
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
  gap: 2px;
  width: 100%;
  height: 100%;
}

.playlist-card__mosaic--single {
  grid-template-columns: 1fr;
  grid-template-rows: 1fr;
  gap: 0;
}

.playlist-card__mosaic img {
  width: 100%;
  height: 100%;
  min-height: 0;
  object-fit: cover;
  display: block;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

@media (hover: hover) {
  .playlist-card:hover .playlist-card__mosaic img {
    transform: scale(1.04);
  }
}

.playlist-card__badge,
.playlist-card__explore {
  position: absolute;
  z-index: var(--n-z-content);
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 8px;
  border-radius: var(--n-radius-xs);
  background: rgba(4, 9, 11, 0.74);
  backdrop-filter: var(--n-blur-sm);
  -webkit-backdrop-filter: var(--n-blur-sm);
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.playlist-card__badge {
  top: var(--n-space-2);
  left: var(--n-space-2);
}

.playlist-card__explore {
  right: var(--n-space-2);
  bottom: var(--n-space-2);
  color: var(--n-text);
}

.playlist-card__body {
  padding: var(--n-space-3);
}

.playlist-card__title {
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  line-height: var(--n-leading-tight);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playlist-card__meta {
  display: flex;
  align-items: center;
  gap: 5px;
  min-width: 0;
  margin-top: var(--n-space-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playlist-card__dot {
  color: var(--n-text-faint);
}

.playlist-card__reason {
  min-height: 2.7em;
  margin-top: var(--n-space-2);
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: var(--n-leading-normal);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.playlist-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-3);
  min-height: 120px;
  padding: var(--n-space-6);
  border: 1px dashed var(--n-line);
  border-radius: var(--n-radius-lg);
  color: var(--n-text-muted);
}

/* ==================== 加载态 ==================== */
.loading {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--n-space-4);
  padding: var(--n-space-16) 0;
  color: var(--n-text-muted);
}

/* ==================== 区块 ==================== */
.section {
  margin-bottom: clamp(28px, 4vw, 44px);
}

.section:last-child {
  margin-bottom: 0;
}

.section__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-4);
  margin-bottom: var(--n-space-4);
}

.section__heading {
  min-width: 0;
}

.section__title {
  font-size: 1.1rem;
  font-weight: var(--n-weight-semibold);
  letter-spacing: -0.02em;
}

.section__sub {
  margin-top: 2px;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

/* ==================== 横向封面栏 ==================== */
.rail {
  display: flex;
  gap: var(--n-space-4);
  overflow-x: auto;
  scroll-snap-type: x proximity;
  scroll-padding-inline: var(--n-space-1);
  padding: var(--n-space-1) var(--n-space-1) var(--n-space-4);
  -webkit-overflow-scrolling: touch;
}

.rail::-webkit-scrollbar {
  height: 6px;
}

.rail::-webkit-scrollbar-track {
  background: transparent;
}

.rail::-webkit-scrollbar-thumb {
  background: rgba(95, 208, 224, 0.28);
  border-radius: var(--n-radius-pill);
}

@media (hover: hover) {
  .rail::-webkit-scrollbar-thumb:hover {
    background: rgba(95, 208, 224, 0.45);
  }
}

.cover-card {
  position: relative;
  flex: 0 0 auto;
  width: 152px;
  scroll-snap-align: start;
  min-width: 0;
}

/* 整卡点击：用真实 button 覆盖，避免把 role="button" 加在 article 上（ARIA 角色不匹配） */
.cover-card__hit {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  border-radius: var(--n-radius-sm);
  cursor: pointer;
  z-index: 1;
}

.cover-card__hit:focus-visible {
  outline: var(--n-focus-ring);
  outline-offset: 3px;
  border-radius: var(--n-radius-sm);
}

.cover-card__art {
  position: relative;
  aspect-ratio: 1;
  border-radius: var(--n-radius);
  overflow: hidden;
  border: 1px solid var(--n-line);
  background: var(--n-surface-soft);
  transition: border-color var(--n-duration-fast) var(--n-ease);
}

.cover-card__art img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

@media (hover: hover) {
  .cover-card:hover .cover-card__art {
    border-color: var(--n-line-strong);
  }

  .cover-card:hover .cover-card__art img {
    transform: scale(1.05);
  }
}

.cover-card__rank {
  position: absolute;
  top: var(--n-space-2);
  left: var(--n-space-2);
  display: grid;
  place-items: center;
  min-width: 24px;
  height: 24px;
  padding: 0 6px;
  border-radius: var(--n-radius-xs);
  background: rgba(4, 9, 11, 0.72);
  backdrop-filter: var(--n-blur-sm);
  -webkit-backdrop-filter: var(--n-blur-sm);
  color: var(--n-text);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-bold);
  font-variant-numeric: tabular-nums;
}

.cover-card__play {
  position: absolute;
  right: var(--n-space-2);
  bottom: var(--n-space-2);
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: var(--n-radius-sm);
  background: var(--n-accent);
  color: var(--n-text-inverse);
  opacity: 0;
  transform: translateY(6px);
  transition: opacity var(--n-duration-fast) var(--n-ease), transform var(--n-duration-fast) var(--n-ease);
}

@media (hover: hover) {
  .cover-card:hover .cover-card__play {
    opacity: 1;
    transform: none;
  }
}

@media (hover: none) {
  .cover-card__play {
    opacity: 1;
    transform: none;
  }
}

.cover-card__title {
  display: flex;
  align-items: center;
  gap: var(--n-space-1);
  min-width: 0;
  margin-top: var(--n-space-3);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

.cover-card__title-text {
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.cover-card__artist {
  margin-top: 1px;
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ==================== 响应式 ==================== */
@media (max-width: 900px) {
  .hero {
    grid-template-columns: 1fr;
    gap: var(--n-space-6);
  }

  .hero__feature {
    width: 100%;
    max-width: 220px;
    justify-self: start;
  }

  .hero__actions :deep(.n-btn) {
    flex: 1 1 auto;
    justify-content: center;
  }

  .playlist-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .lxmc-entry {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .lxmc-entry__actions {
    grid-column: 1 / -1;
    justify-content: flex-start;
    padding-left: calc(76px + var(--n-space-5));
  }
}

/* ==================== 手机竖屏：横向卡片栏 ====================
   原来卡片是 4px 内边距 + 152px 固定宽，在 375px 屏上只能露出 2 张，
   既看不出「还能往右滑」，卡片又没贴到屏幕边缘、与上方标题差 4px。
   改为手机上通行的「出血滚动」：负外边距抵消页面留白，让卡片能滑到
   屏幕边缘，再靠 padding-inline + scroll-padding-inline 把首尾两张
   对齐回正文左边界。 */
@media (max-width: 560px) {
  .rail {
    gap: var(--n-space-3);
    /* 抵消 PageShell 的内容留白（.section 本身无内边距，故可直接出血） */
    margin-inline: calc(-1 * var(--n-content-gutter));
    padding-inline: var(--n-content-gutter);
    /* 吸附位置也按留白计算，滑停时首张与标题对齐 */
    scroll-padding-inline: var(--n-content-gutter);
    /* 触摸端不用横向滚动条：占高度、且手指本来也拖不到它 */
    scrollbar-width: none;
  }

  .rail::-webkit-scrollbar {
    display: none;
  }

  /* 128px 可露出约 2.5 张 —— 露出的半张本身就是「可横滑」的提示 */
  .cover-card {
    width: 128px;
  }

  .cover-card__play {
    width: 40px;
    height: 40px;
  }

  .cover-card__title {
    margin-top: var(--n-space-2);
  }

  .quick__card {
    gap: var(--n-space-3);
  }

  .lxmc-entry {
    grid-template-columns: 1fr;
  }

  .lxmc-entry__icon {
    width: 62px;
    height: 62px;
  }

  .lxmc-entry__actions {
    grid-column: auto;
    flex-direction: column;
    align-items: stretch;
    padding-left: 0;
  }

  .lxmc-entry__actions :deep(.n-btn) {
    width: 100%;
  }

  /* Hero 动作区：900px 断点给按钮加的 flex:1 1 auto 会在窄屏把
     「播放热门 / 下载客户端」挤在同一行、各占一半宽，主次不分且点按偏小。
     手机上改为纵向堆叠、各自撑满整行：主操作更醒目，热区也更大。 */
  .hero__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .hero__actions :deep(.n-btn) {
    flex: 0 0 auto;
    width: 100%;
  }

  /* 动作区已撑满整行，220px 的封面再左对齐会在右侧留出明显空档，
     手机端让视觉图居中，容器更平衡。 */
  .hero__feature {
    justify-self: center;
  }

  .playlist-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: var(--n-space-3);
  }

  .playlist-card__body {
    padding: var(--n-space-2) var(--n-space-1) var(--n-space-1);
  }
}

/* ==========================================================================
   新版首页工作台
   --------------------------------------------------------------------------
   旧首页样式保留在文件内供历史页面分支复用；以下规则覆盖首页当前结构，
   让首页成为左侧导航下的双栏内容工作区。
   ========================================================================== */
.home-page {
  max-width: 1560px;
  padding-top: clamp(22px, 3vw, 38px);
}

.home-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--n-space-6);
  margin-bottom: 26px;
}

.home-head__kicker,
.section-head__eyebrow {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
  color: var(--n-accent);
  font-size: 0.68rem;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.12em;
  text-transform: uppercase;
}

.home-head__kicker span {
  display: inline-block;
  width: 22px;
  height: 2px;
  background: var(--n-accent);
}

.home-head__title {
  font-size: clamp(1.9rem, 3vw, 2.65rem);
  letter-spacing: -0.04em;
}

.home-head__greeting {
  margin-top: 7px;
  color: var(--n-text-muted);
}

.home-head__meta {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  min-width: 180px;
  padding: 12px 16px;
  border-left: 1px solid var(--n-line);
}

.home-head__meta-label {
  color: var(--n-accent);
  font-size: var(--n-text-xs);
}

.home-head__meta strong,
.home-head__meta small {
  max-width: 230px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-head__meta strong {
  font-size: var(--n-text-sm);
}

.home-head__meta small {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.home-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.62fr) minmax(300px, 0.78fr);
  align-items: start;
  gap: clamp(22px, 3vw, 36px);
}

.home-main,
.home-side {
  min-width: 0;
}

.home-side {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.feature-panel {
  position: relative;
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(190px, 260px);
  align-items: center;
  min-height: 316px;
  margin-bottom: 30px;
  padding: clamp(24px, 4vw, 42px);
  overflow: hidden;
  border: 1px solid var(--n-line);
  border-radius: 18px;
  background: var(--n-surface);
  box-shadow: var(--n-shadow);
}

.feature-panel__backdrop {
  position: absolute;
  inset: -18%;
  background-position: center;
  background-size: cover;
  filter: blur(46px) saturate(0.8);
  opacity: 0.24;
  transform: scale(1.08);
}

.feature-panel__wash {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(90deg, rgba(23, 28, 38, 0.98) 12%, rgba(23, 28, 38, 0.8) 57%, rgba(23, 28, 38, 0.36)),
    linear-gradient(0deg, rgba(23, 28, 38, 0.6), transparent 60%);
}

.feature-panel__copy,
.feature-panel__art {
  position: relative;
  z-index: 1;
}

.feature-panel__copy {
  max-width: 500px;
}

.feature-panel__eyebrow {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--n-accent-strong);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-semibold);
}

.feature-panel h2 {
  max-width: 10ch;
  margin: 14px 0 12px;
  color: var(--n-text);
  font-size: clamp(2rem, 4vw, 3.45rem);
  line-height: 1.03;
  letter-spacing: -0.055em;
}

.feature-panel p {
  max-width: 44ch;
  color: var(--n-text-muted);
  font-size: var(--n-text-base);
}

.feature-panel p strong {
  color: var(--n-text);
}

.feature-panel__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 24px;
}

.feature-panel__art {
  justify-self: end;
  width: min(100%, 252px);
  aspect-ratio: 1;
  padding: 0;
  overflow: hidden;
  border: 1px solid var(--n-line-strong);
  border-radius: 16px;
  background: var(--n-surface-sunken);
  box-shadow: 0 22px 46px rgba(0, 0, 0, 0.35);
  cursor: pointer;
}

.feature-panel__art > img,
.feature-panel__mosaic {
  display: block;
  width: 100%;
  height: 100%;
}

.feature-panel__art > img {
  object-fit: cover;
  transition: transform var(--n-duration-slow) var(--n-ease);
}

.feature-panel__mosaic {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 2px;
}

.feature-panel__mosaic img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.feature-panel__art-play {
  position: absolute;
  right: 14px;
  bottom: 14px;
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  color: var(--n-text-inverse);
  background: var(--n-accent);
  box-shadow: 0 10px 22px rgba(0, 0, 0, 0.32);
}

@media (hover: hover) {
  .feature-panel__art:hover > img {
    transform: scale(1.05);
  }
}

.section-block {
  margin-bottom: 34px;
}

.section-head,
.side-panel__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
  margin-bottom: 15px;
}

.section-head h2,
.side-panel__head h2 {
  font-size: 1.12rem;
  letter-spacing: -0.02em;
}

.section-head > div > p:last-child {
  margin-top: 4px;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.section-head__eyebrow {
  margin-bottom: 4px;
  color: var(--n-text-faint);
  font-size: 0.62rem;
}

.playlist-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.playlist-card {
  display: block;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--n-line);
  border-radius: 13px;
  color: inherit;
  background: var(--n-surface);
  transition: transform var(--n-duration-fast) var(--n-ease), border-color var(--n-duration-fast) var(--n-ease), background var(--n-duration-fast) var(--n-ease);
}

.playlist-card:hover {
  border-color: var(--n-line-strong);
  background: var(--n-surface-hover);
  transform: translateY(-3px);
}

.playlist-card__art {
  border-radius: 0;
}

.playlist-card h3 {
  padding: 12px 12px 0;
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-semibold);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playlist-card > p {
  min-height: 2.8em;
  padding: 4px 12px 12px;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
}

.side-panel,
.quick-panel {
  padding: 20px;
  border: 1px solid var(--n-line);
  border-radius: 16px;
  background: rgba(23, 28, 38, 0.7);
}

.side-panel__head {
  align-items: flex-end;
}

.rank-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.rank-row {
  display: grid;
  grid-template-columns: 22px 40px minmax(0, 1fr) 28px;
  align-items: center;
  gap: 10px;
  width: 100%;
  min-height: 58px;
  padding: 7px 5px;
  border-radius: 10px;
  color: var(--n-text);
  text-align: left;
  transition: background var(--n-duration-fast) var(--n-ease);
}

.rank-row:hover {
  background: var(--n-surface-hover);
}

.rank-row__number {
  color: var(--n-text-faint);
  font-family: var(--n-font-mono);
  font-size: 0.68rem;
  text-align: center;
}

.rank-row:nth-child(-n + 3) .rank-row__number {
  color: var(--n-accent);
}

.rank-row img {
  width: 40px;
  height: 40px;
  border-radius: 9px;
  object-fit: cover;
  background: var(--n-surface-sunken);
}

.rank-row__copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 2px;
}

.rank-row__copy strong,
.rank-row__copy small {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rank-row__copy strong {
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

.rank-row__copy small {
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
}

.rank-row__icon {
  color: var(--n-text-faint);
}

.rank-row:hover .rank-row__icon {
  color: var(--n-accent);
}

.side-panel__loading {
  display: grid;
  place-items: center;
  min-height: 300px;
}

.import-panel {
  position: relative;
  overflow: hidden;
  padding: 22px;
  border: 1px solid var(--n-accent-line);
  border-radius: 16px;
  background:
    linear-gradient(145deg, rgba(240, 181, 110, 0.15), transparent 62%),
    var(--n-surface);
}

.import-panel::after {
  content: '.LXMC';
  position: absolute;
  top: 17px;
  right: 16px;
  color: rgba(240, 181, 110, 0.18);
  font-size: 1.35rem;
  font-weight: var(--n-weight-bold);
  letter-spacing: 0.06em;
}

.import-panel__icon {
  display: grid;
  place-items: center;
  width: 42px;
  height: 42px;
  margin-bottom: 22px;
  border-radius: 12px;
  color: var(--n-accent-strong);
  background: var(--n-accent-soft);
}

.import-panel h2 {
  max-width: 15ch;
  margin: 8px 0 8px;
  font-size: 1.15rem;
  line-height: 1.2;
}

.import-panel > p:not(.section-head__eyebrow) {
  min-height: 4.2em;
  margin-bottom: 18px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: 1.55;
}

.quick-panel {
  padding-bottom: 12px;
}

.quick-row {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) 16px;
  align-items: center;
  gap: 9px;
  min-height: 46px;
  border-top: 1px solid var(--n-line-subtle);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.quick-row span {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: 8px;
  color: var(--n-accent);
  background: var(--n-accent-soft);
}

.quick-row strong {
  color: var(--n-text);
  font-weight: var(--n-weight-medium);
}

.quick-row > :last-child {
  color: var(--n-text-faint);
}

.cover-rail {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
}

.cover-card {
  width: auto;
}

.cover-card__art {
  border-radius: 13px;
}

.cover-card__play {
  right: 9px;
  bottom: 9px;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 0;
  opacity: 1;
  transform: none;
  cursor: pointer;
}

.cover-card h3 {
  margin-top: 9px;
  overflow: hidden;
  color: var(--n-text);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cover-card p {
  margin-top: 2px;
  overflow: hidden;
  color: var(--n-text-faint);
  font-size: var(--n-text-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .home-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .home-side {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    align-items: start;
  }

  .quick-panel {
    grid-column: 1 / -1;
  }

  .cover-rail {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

@media (max-width: 700px) {
  .home-head {
    align-items: flex-start;
  }

  .home-head__meta {
    display: none;
  }

  .feature-panel {
    grid-template-columns: 1fr;
    gap: 24px;
    min-height: 0;
  }

  .feature-panel h2 {
    max-width: 12ch;
  }

  .feature-panel__art {
    justify-self: start;
    width: min(100%, 210px);
  }

  .home-side {
    display: flex;
  }

  .playlist-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .cover-rail {
    display: flex;
    overflow-x: auto;
    margin-inline: calc(-1 * var(--n-content-gutter));
    padding: 0 var(--n-content-gutter) 8px;
    scrollbar-width: none;
  }

  .cover-rail::-webkit-scrollbar {
    display: none;
  }

  .cover-card {
    flex: 0 0 132px;
  }
}

@media (max-width: 560px) {
  .home-page {
    padding-top: 20px;
  }

  .home-head {
    margin-bottom: 20px;
  }

  .home-head__title {
    font-size: 2rem;
  }

  .feature-panel {
    margin-inline: -4px;
    padding: 22px;
  }

  .feature-panel h2 {
    font-size: 2.2rem;
  }

  .feature-panel__actions {
    flex-direction: column;
    align-items: stretch;
  }

  .feature-panel__actions :deep(.n-btn) {
    width: 100%;
  }

  .section-block {
    margin-bottom: 28px;
  }

  .playlist-grid {
    gap: 10px;
  }

  .side-panel,
  .quick-panel,
  .import-panel {
    padding: 16px;
  }
}
</style>
