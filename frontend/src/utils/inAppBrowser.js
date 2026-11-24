/**
 * App 内置浏览器识别。
 *
 * 仅用于前端交互提示：内置 WebView 通常会拦截 nekomusic:// 自定义协议，
 * 提前识别可以避免一起听页一直停留在「正在打开」状态。
 *
 * 规则按具体 App 标识优先匹配，再用 Android WebView 与 iOS WKWebView 的
 * UA 特征兜底。不能识别所有裁剪过 UA 的 WebView，未知环境仍走正常拉起流程。
 */
const IN_APP_BROWSER_RULES = [
  { name: '企业微信', pattern: /\bwxwork\//i },
  { name: '微信', pattern: /micromessenger/i },
  { name: 'QQ', pattern: /(?:^|[\s;()])qq\//i },
  { name: 'QQ 空间', pattern: /qzone\//i },
  { name: '腾讯系内置浏览器', pattern: /tencenttraveler/i },
  { name: '钉钉', pattern: /dingtalk/i },
  { name: '飞书', pattern: /(?:^|[\s;(])lark\/|feishu\//i },
  { name: '支付宝', pattern: /alipayclient|aliapp\(ap\//i },
  { name: '淘宝 / 天猫', pattern: /aliapp\((?:tb|tm)\//i },
  { name: '微博', pattern: /weibo/i },
  { name: '抖音', pattern: /aweme|douyin|bytedancewebview/i },
  { name: '今日头条', pattern: /newsarticle|toutiao/i },
  { name: '小红书', pattern: /\bxhs\b|xhs\/|xiaohongshu/i },
  { name: '百度', pattern: /baiduboxapp/i },
  { name: '快手', pattern: /\bkwai\b|kuaishou/i },
  { name: '哔哩哔哩', pattern: /bilibili|bili-universal|\bbili\//i },
  { name: '京东', pattern: /\bjdapp\b/i },
  { name: '拼多多', pattern: /pinduoduo/i },
  { name: '美团', pattern: /meituan/i },
  { name: '知乎', pattern: /zhihu/i },
  { name: '网易云音乐', pattern: /neteasemusic|neteasecloudmusic/i },
  { name: '豆瓣', pattern: /douban/i },
  { name: 'Facebook', pattern: /fban\/|fbav\//i },
  { name: 'Instagram', pattern: /instagram/i },
  { name: 'LINE', pattern: /(?:^|[\s;(])line\//i },
  { name: 'X', pattern: /twitter/i },
  { name: 'LinkedIn', pattern: /linkedinapp/i },
  { name: 'Snapchat', pattern: /snapchat/i },
  { name: 'TikTok', pattern: /musical_ly|tiktok/i },
]

const ANDROID_WEBVIEW_RE = /;\s*wv\b/i
const IOS_DEVICE_RE = /iphone|ipad|ipod/i
const IOS_SAFARI_RE = /safari\//i

/**
 * @param {string} [userAgent] 可显式传入 UA，便于单测；默认读取 navigator.userAgent
 * @returns {{ name: string } | null}
 */
export function detectInAppBrowser(userAgent) {
  const ua = String(
    userAgent ?? (typeof navigator === 'undefined' ? '' : navigator.userAgent || ''),
  ).trim()
  if (!ua) return null

  const matched = IN_APP_BROWSER_RULES.find((rule) => rule.pattern.test(ua))
  if (matched) return { name: matched.name }

  // Android 系统 WebView 的标准 UA 标识；Chrome Custom Tabs 不包含该标记。
  if (ANDROID_WEBVIEW_RE.test(ua)) return { name: '内置浏览器' }

  // iOS WKWebView 默认 UA 不含 Safari/，而 Chrome、Firefox、Edge 等独立浏览器会保留。
  if (IOS_DEVICE_RE.test(ua) && !IOS_SAFARI_RE.test(ua)) return { name: '内置浏览器' }

  return null
}
