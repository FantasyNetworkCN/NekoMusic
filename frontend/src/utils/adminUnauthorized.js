// 管理后台权限失效兜底
// ------------------------------------------------------------
// 后台页面可能通过 fetch、XHR 或 axios 发起管理请求。NekoAuth 令牌失效返回 401，
// 角色被撤销或不是 ADMIN 返回 403；
// 各页面若只提示「加载失败」，用户会停留在已经不可用的后台页面。这里在请求入口统一处理：
// 清掉管理员本地状态并直接回到登录页。
//
// 用户令牌就是 NekoAuth access token，不在这里清除；只清理后台身份缓存。
import { clearAdminIdentity } from '@/api/adminAuth.js'

const ADMIN_LOGIN_PATH = '/admin/login'

let installed = false
let redirecting = false

function currentPath(router) {
  return router.currentRoute.value.path || window.location.pathname
}

function isAdminPage(router) {
  const path = currentPath(router)
  return (path === '/admin' || path.startsWith('/admin/')) && path !== ADMIN_LOGIN_PATH
}

function clearAdminSession() {
  try {
    clearAdminIdentity()
  } catch {
    // 隐私模式等场景下 localStorage 可能不可用；仍继续回登录页。
  }
}

function installHandler(router) {
  const handleUnauthorized = () => {
    if (!isAdminPage(router) || redirecting) return
    redirecting = true
    clearAdminSession()
    const current = router.currentRoute.value
    Promise.resolve(router.replace({
      path: ADMIN_LOGIN_PATH,
      query: current.path.startsWith('/admin') ? { returnTo: current.fullPath, denied: '1' } : {},
    }))
      .catch(() => {})
      .finally(() => {
        redirecting = false
      })
  }

  const previousFetch = window.fetch.bind(window)
  window.fetch = async (...args) => {
    const response = await previousFetch(...args)
    const input = args[0]
    const url =
      typeof input === 'string' || input instanceof URL
        ? String(input)
        : input instanceof Request
          ? input.url
          : ''
    if ((response.status === 401 || response.status === 403)
        && String(url).includes('/api/admin/')) {
      handleUnauthorized()
    }
    return response
  }

  if (typeof XMLHttpRequest === 'undefined') return

  const proto = XMLHttpRequest.prototype
  const originalOpen = proto.open
  const originalSend = proto.send

  proto.open = function (method, url, ...rest) {
    this.__nekoAdminUnauthorizedUrl = url
    this.__nekoAdminUnauthorizedHandled = false
    return originalOpen.call(this, method, url, ...rest)
  }

  proto.send = function (...args) {
    if (!this.__nekoAdminUnauthorizedListener) {
      this.__nekoAdminUnauthorizedListener = () => {
        if ((this.status !== 401 && this.status !== 403) || this.__nekoAdminUnauthorizedHandled) return
        if (!String(this.__nekoAdminUnauthorizedUrl || '').includes('/api/admin/')) return
        this.__nekoAdminUnauthorizedHandled = true
        handleUnauthorized()
      }
      this.addEventListener('load', this.__nekoAdminUnauthorizedListener)
    }
    return originalSend.apply(this, args)
  }
}

/**
 * 安装管理员 401 兜底。须在 installRequestHeaders() 之后调用。
 */
export function installAdminUnauthorizedHandler(router) {
  if (installed || typeof window === 'undefined' || typeof window.fetch !== 'function') return
  installed = true
  installHandler(router)
}
