import API_CONFIG from '@/config/apiConfig.js'

export const ADMIN_INFO_KEY = 'adminInfo'

export function clearAdminIdentity() {
  try {
    localStorage.removeItem(ADMIN_INFO_KEY)
  } catch {
    // 隐私模式下 localStorage 不可用时仍继续。
  }
}

/**
 * 用当前 NekoAuth userToken 确认后台权限。
 * @returns {Promise<'admin'|'forbidden'|'anonymous'|'unavailable'>}
 */
export async function refreshAdminIdentity() {
  const token = localStorage.getItem('userToken')
  if (!token) {
    clearAdminIdentity()
    return 'anonymous'
  }
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/current`, {
      headers: { Authorization: `Bearer ${token}` },
      cache: 'no-store',
    })
    const body = await response.json().catch(() => ({}))
    if (response.ok && body?.success && body?.data?.admin) {
      const admin = body.data.admin
      const info = {
        ...admin,
        role: String(admin.role || '').toLowerCase(),
      }
      localStorage.setItem(ADMIN_INFO_KEY, JSON.stringify(info))
      return info.role === 'admin' ? 'admin' : 'forbidden'
    }
    clearAdminIdentity()
    if (response.status === 403) return 'forbidden'
    if (response.status === 401) return 'anonymous'
    return 'unavailable'
  } catch {
    return 'unavailable'
  }
}
