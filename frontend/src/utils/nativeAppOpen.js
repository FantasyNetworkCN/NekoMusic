import { replaceUrlSafely } from './routerHistory'
import { isMobileDevice } from './mobile'
/**
 * 移动端进入详情 / 歌单页时尝试拉起原生 App。
 *
 * Android：只发起 **一次** VIEW intent（含 S.browser_fallback_url）。
 * 不再做「首次触摸重试」或延迟 nekomusic://，避免同一进入连弹多个系统框。
 * 若 Chrome 在无手势下完全静默失败，用户可刷新或从下载页 / App 内打开。
 *
 * 无 App 时 intent 的 fallback 回到当前页并带 nekoweb=1，避免死循环（见 stripSkipQueryFromUrl）。
 *
 * 微信 / QQ 内置浏览器常拦截，需在系统浏览器中打开。
 */
const ANDROID_PKG = 'com.neko.music'
const SKIP_QUERY = 'nekoweb'

/** 同一条资源在几秒内只尝试拉起一次（含 Vue Strict Mode 双挂载、路由重复触发） */
const OPEN_DEDUPE_MS = 12000



function stripSkipQueryFromUrl() {
  try {
    const u = new URL(window.location.href)
    if (u.searchParams.get(SKIP_QUERY) !== '1') return
    u.searchParams.delete(SKIP_QUERY)
    const next = u.pathname + (u.searchParams.toString() ? `?${u.searchParams}` : '') + u.hash
    // 用安全替换：直接 replaceState({}) 会抹掉 router 的 state
    replaceUrlSafely(next)
  } catch {
    /* ignore */
  }
}

/** 按 Android Intent URI 规范构造 VIEW 跳转，避免把 data 当作非法 extra。 */
function buildAndroidViewIntent(schemeUrl, fallbackUrl) {
  const target = new URL(schemeUrl)
  const scheme = target.protocol.slice(0, -1)
  return (
    `intent://${target.host}${target.pathname}${target.search}` +
    `#Intent;action=android.intent.action.VIEW;scheme=${scheme};` +
    `package=${ANDROID_PKG};S.browser_fallback_url=${encodeURIComponent(fallbackUrl)};end`
  )
}

/** 单次导航：程序化 `<a>` 点击；失败再 `assign` */
function navigateLikeUserClick(url) {
  try {
    const a = document.createElement('a')
    a.setAttribute('href', url)
    a.setAttribute('rel', 'noopener noreferrer')
    a.style.display = 'none'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
  } catch {
    try {
      window.location.assign(url)
    } catch {
      /* ignore */
    }
  }
}

function shouldFireNativeOpen(kind, id) {
  try {
    const key = `neko-native-open:${kind}:${id}`
    const now = Date.now()
    const prev = sessionStorage.getItem(key)
    if (prev && now - Number(prev) < OPEN_DEDUPE_MS) return false
    sessionStorage.setItem(key, String(now))
    return true
  } catch {
    return true
  }
}

/**
 * @param {'detail'|'playlist'} kind
 * @param {string} id
 * @param {string} webPath
 * @param {string} schemeUrl
 */
function tryOpenNative(kind, id, webPath, schemeUrl) {
  if (!id || !isMobileDevice()) return

  try {
    const u = new URL(window.location.href)
    if (u.searchParams.get(SKIP_QUERY) === '1') {
      stripSkipQueryFromUrl()
      return
    }
  } catch {
    /* ignore */
  }

  if (!shouldFireNativeOpen(kind, id)) return

  const origin = window.location.origin
  const pathOnly = webPath.startsWith('/') ? webPath : `/${webPath}`
  const webBase = `${origin}${pathOnly}`
  const sep = webBase.includes('?') ? '&' : '?'
  const fallbackUrl = `${webBase}${sep}${SKIP_QUERY}=1`
  const ua = navigator.userAgent || ''

  if (!/Android/i.test(ua)) {
    window.location.assign(schemeUrl)
    return
  }

  const intent = buildAndroidViewIntent(schemeUrl, fallbackUrl)

  navigateLikeUserClick(intent)
}

export function tryOpenMusicDetailInApp(musicId) {
  tryOpenNative('detail', String(musicId), `/detail/${musicId}`, `nekomusic://player/${musicId}`)
}

export function tryOpenPlaylistInApp(playlistId) {
  tryOpenNative('playlist', String(playlistId), `/playlist/${playlistId}`, `nekomusic://playlist/${playlistId}`)
}

/**
 * 一起听分享页只负责把随机房间 token 交给原生客户端，不承载播放器或房间界面。
 *
 * @param {string} token 后端生成的房间 token
 * @param {{ force?: boolean }} [options]
 * @returns {boolean} 是否发起了拉起 App 的导航
 */
export function tryOpenTogetherInApp(token, options = {}) {
  const roomToken = String(token || '').trim()
  if (!/^[A-Za-z0-9_-]{8,128}$/.test(roomToken)) return false

  try {
    const u = new URL(window.location.href)
    if (u.searchParams.get(SKIP_QUERY) === '1') {
      stripSkipQueryFromUrl()
      return false
    }
  } catch {
    /* ignore */
  }

  if (!options.force && !shouldFireNativeOpen('together', roomToken)) return false

  const webPath = `/together/${roomToken}`
  const schemeUrl = `nekomusic://together/${roomToken}`
  const ua = navigator.userAgent || ''

  if (!/Android/i.test(ua)) {
    window.location.assign(schemeUrl)
    return true
  }

  const fallbackUrl = `${window.location.origin}${webPath}?${SKIP_QUERY}=1`
  const intent = buildAndroidViewIntent(schemeUrl, fallbackUrl)
  navigateLikeUserClick(intent)
  return true
}
