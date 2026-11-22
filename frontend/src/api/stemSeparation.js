import API_CONFIG from '@/config/apiConfig.js'

function absoluteMediaUrl(value) {
  if (!value) return ''
  if (/^https?:\/\//i.test(value)) return value
  return `${API_CONFIG.BASE_URL}${value.startsWith('/') ? value : `/${value}`}`
}

function parseSseFrame(eventName, payload) {
  if (!payload) return null
  try {
    return {
      event: eventName || 'message',
      data: JSON.parse(payload),
    }
  } catch {
    return null
  }
}

function backendErrorMessage(rawBody, status) {
  try {
    const parsed = JSON.parse(rawBody)
    if (parsed && typeof parsed.message === 'string' && parsed.message.trim()) {
      return parsed.message
    }
  } catch {
    // 非 JSON 错误体走通用状态码。
  }
  return `HTTP ${status}`
}

/**
 * 请求服务端人声 / 伴奏分离，等待 done 后返回两条完整媒体地址。
 * 中间事件通过 onProgress 回调，便于 UI 显示队列和处理进度。
 */
export async function requestStemSeparation(musicId, { signal, onProgress } = {}) {
  const response = await fetch(
    `${API_CONFIG.BASE_URL}/api/music/file/${musicId}/stream?separate=1`,
    {
      method: 'GET',
      cache: 'no-store',
      headers: { Accept: 'text/event-stream' },
      signal,
    }
  )
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(backendErrorMessage(body, response.status))
  }
  if (!response.body) throw new Error('stream_unsupported')

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let eventName = ''
  let dataLines = []

  const dispatch = (frame) => {
    if (!frame) return null
    onProgress?.(frame.event, frame.data)
    if (frame.event === 'error') {
      throw new Error(frame.data?.message || 'separation_failed')
    }
    if (frame.event === 'done') {
      return {
        vocalsUrl: absoluteMediaUrl(frame.data?.vocalsUrl),
        accompanimentUrl: absoluteMediaUrl(frame.data?.accompanimentUrl),
      }
    }
    return null
  }

  while (true) {
    const { done, value } = await reader.read()
    buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
    let newline
    while ((newline = buffer.indexOf('\n')) >= 0) {
      let line = buffer.slice(0, newline)
      buffer = buffer.slice(newline + 1)
      if (line.endsWith('\r')) line = line.slice(0, -1)
      if (!line) {
        const result = dispatch(parseSseFrame(eventName, dataLines.join('\n')))
        eventName = ''
        dataLines = []
        if (result) {
          reader.cancel().catch(() => {})
          return result
        }
      } else if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).trimStart())
      }
    }
    if (done) break
  }

  const finalResult = dispatch(parseSseFrame(eventName, dataLines.join('\n')))
  if (finalResult) return finalResult
  throw new Error('separation_interrupted')
}
