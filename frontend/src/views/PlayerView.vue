<template>
  <div class="music-detail-view">
    <div class="detail-container" v-if="currentMusic">
      <div class="content-wrapper">
        <!-- 左侧：音乐详情 -->
        <div class="detail-section">
          <!-- 音乐封面 -->
          <div class="cover-section">
            <img 
              :src="getCoverUrl(currentMusic.id)" 
              :alt="currentMusic.title"
              class="music-cover"
              @error="handleImageError"
            />
          </div>
          
          <!-- 音乐信息 -->
          <div class="music-info">
            <h1 class="music-title">{{ currentMusic.title }}</h1>
            <p class="music-artist">艺术家：{{ currentMusic.artist }}</p>
            <p class="music-album" v-if="currentMusic.album">专辑：{{ currentMusic.album }}</p>
          </div>
          
          <!-- 操作按钮 -->
          <div class="action-buttons">
            <button @click="togglePlayPause" class="play-btn">
              {{ isPlaying ? '暂停音乐' : '播放音乐' }}
            </button>
            <button @click="downloadMusic" class="download-btn">
              下载音乐
            </button>
          </div>
        </div>
        
        <!-- 右侧：歌词显示 -->
        <div class="lyrics-section" v-if="parsedLyrics.length > 0">
          <h3>歌词</h3>
          <div class="lyrics-container">
            <div class="lyrics-content" ref="lyricsContent">
              <div 
                v-for="(line, index) in parsedLyrics" 
                :key="index" 
                class="lyric-line"
                :class="getLyricLineClass(index)"
              >
                {{ line.text }}
              </div>
            </div>
          </div>
        </div>
      </div>
      
      <!-- 隐藏的音频元素 -->
      <audio 
        ref="audioPlayer" 
        :src="`${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.id}`" 
        @ended="onAudioEnded"
        @timeupdate="onTimeUpdate"
        @loadedmetadata="onLoadedMetadata"
      />
    </div>
    
    <div v-else class="loading">
      <p>加载音乐详情中...</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const route = useRoute()
const currentMusic = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const lyrics = ref('')
const parsedLyrics = ref([])
const lyricsContent = ref(null)
const audioPlayer = ref(null)

// 目前全局播放器的引用（用于同步状态）
let globalPlayerState = JSON.parse(localStorage.getItem('globalPlayerState') || '{"isPlaying": false, "currentTime": 0, "duration": 0}')

// 获取音乐详情
const fetchMusicDetail = async (musicId) => {
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json'
      }
    })
    
    const data = await response.json()
    if (data.success) {
      currentMusic.value = data.data;
      // 加载歌词
      loadLyrics(musicId)
    } else {
      console.error('获取音乐详情失败:', data.message)
    }
  } catch (error) {
    console.error('请求音乐详情时出错:', error)
  }
}

// 获取歌词
const getLyricsUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/lyrics/${musicId}`
}

// 加载歌词
const loadLyrics = async (musicId) => {
  try {
    const response = await fetch(getLyricsUrl(musicId))
    if (response.ok) {
      const data = await response.json()
      if (data.success) {
        lyrics.value = data.data
        parseLrcLyrics(data.data)
      } else {
        lyrics.value = ''
        parsedLyrics.value = []
      }
    } else {
      lyrics.value = ''
      parsedLyrics.value = []
    }
  } catch (error) {
    console.error('加载歌词失败:', error)
    lyrics.value = ''
    parsedLyrics.value = []
  }
}

// 解析LRC歌词格式
const parseLrcLyrics = (lrcText) => {
  if (!lrcText) {
    parsedLyrics.value = []
    return
  }
  
  const lines = lrcText.split('\n')
  const parsed = []
  
  for (const line of lines) {
    // 匹配 [mm:ss.xx] 或 [mm:ss.xxx] 格式的时间标签
    const timeRegex = /\[(\d{2}):(\d{2})\.(\d{2,3})\]/g
    let match
    const text = line.replace(timeRegex, '').trim()
    
    while ((match = timeRegex.exec(line)) !== null) {
      const minutes = parseInt(match[1])
      const seconds = parseInt(match[2])
      const milliseconds = parseInt(match[3])
      // 根据毫秒长度调整（LRC格式可能使用2位或3位毫秒）
      let millisecondsDivisor
      if (milliseconds.toString().length === 2) {
        millisecondsDivisor = 100 // 两位毫秒，如 .25
      } else {
        millisecondsDivisor = 1000 // 三位毫秒，如 .250
      }
      const timeInSeconds = minutes * 60 + seconds + (milliseconds / millisecondsDivisor)
      parsed.push({
        time: timeInSeconds,
        text: text
      })
    }
  }
  
  // 按时间排序
  parsed.sort((a, b) => a.time - b.time)
  parsedLyrics.value = parsed
}

// 播放/暂停音乐
const togglePlayPause = () => {
  if (audioPlayer.value && currentMusic.value) {
    if (isPlaying.value) {
      // 暂停
      fadeOut(audioPlayer.value)
      isPlaying.value = false
    } else {
      // 播放
      if (audioPlayer.value.paused) {
        audioPlayer.value.volume = 0
        audioPlayer.value.play().catch(e => console.log('播放被阻止:', e))
      }
      fadeIn(audioPlayer.value)
      isPlaying.value = true
    }
  }
}

// 音量淡出效果
const fadeOut = (audioElement) => {
  if (!audioElement) return

  const fadeDuration = 300 // 毫秒
  const initialVolume = audioElement.volume || 1
  const fadeInterval = 50 // 毫秒
  const decrement = (initialVolume * fadeInterval) / fadeDuration

  const fade = () => {
    if (audioElement.volume > 0.1) { // 避免完全静音时的数值问题
      audioElement.volume = Math.max(0, audioElement.volume - decrement)
      setTimeout(fade, fadeInterval)
    } else {
      audioElement.volume = 0
      audioElement.pause()
      isPlaying.value = false
    }
  }

  fade()
}

// 音量淡入效果
const fadeIn = (audioElement) => {
  if (!audioElement) return

  const fadeDuration = 300 // 毫秒
  const targetVolume = 1 // 可以根据需要调整
  const fadeInterval = 50 // 毫秒
  const increment = (targetVolume * fadeInterval) / fadeDuration

  let currentVolume = audioElement.volume || 0
  const target = Math.min(targetVolume, 1)

  const fade = () => {
    if (currentVolume < target) {
      currentVolume = Math.min(target, currentVolume + increment)
      audioElement.volume = currentVolume
      setTimeout(fade, fadeInterval)
    } else {
      audioElement.volume = target
    }
  }

  fade()
}

// 音频结束事件
const onAudioEnded = () => {
  isPlaying.value = false
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    // 更新歌词位置
    setTimeout(() => calculateLyricPositions(), 0) // 使用setTimeout确保DOM已更新
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
  }
}

// 获取当前歌词索引
const getCurrentLyricIndex = () => {
  if (parsedLyrics.value.length === 0) return -1

  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      return i
    }
  }

  return -1
}

// 判断当前歌词行是否应该高亮
const isActiveLyric = (index) => {
  const currentIndex = getCurrentLyricIndex()
  return currentIndex === index
}

// 获取歌词行类型（active, before, after）
const getLyricLineClass = (index) => {
  const currentIndex = getCurrentLyricIndex()
  if (currentIndex === index) {
    return 'active'
  } else if (currentIndex - 1 === index || currentIndex + 1 === index) {
    // 相邻的歌词行
    return 'before'
  } else {
    // 其他歌词行
    return 'lyric-line'
  }
}

// 计算歌词的top位置
const calculateLyricTop = (index) => {
  const currentIndex = getCurrentLyricIndex()
  if (currentIndex === -1) return 250 // 默认位置

  const positionOffset = index - currentIndex
  const baseTop = 250 // 中间位置，根据容器高度调整
  const verticalOffset = positionOffset * 60 // 每行垂直间距

  return baseTop + verticalOffset
}

// 计算歌词行位置
const calculateLyricPositions = () => {
  if (!lyricsContent.value) return

  const currentIndex = getCurrentLyricIndex()
  if (currentIndex === -1) return

  // 获取所有歌词元素并设置位置
  const lyricElements = lyricsContent.value.children
  const containerHeight = lyricsContent.value.clientHeight || 500

  for (let i = 0; i < lyricElements.length; i++) {
    const element = lyricElements[i]
    if (!element || !element.classList.contains('lyric-line')) continue

    const positionOffset = i - currentIndex
    const baseTop = containerHeight / 2 // 中间位置

    // 设置元素位置
    if (positionOffset === 0) {
      // 当前歌词在中间
      element.style.top = `${baseTop}px`
      element.classList.add('active')
      element.classList.remove('before', 'after')
    } else {
      // 其他歌词根据位置设置样式和位置
      const distance = Math.abs(positionOffset)
      const verticalOffset = positionOffset * 60 // 每行垂直间距
      
      element.style.top = `${baseTop + verticalOffset}px`
      
      if (distance === 1) {
        element.classList.add('before')
        element.classList.remove('active', 'after')
      } else {
        element.classList.remove('active', 'before', 'after')
      }
    }
  }
}

// 滚动到当前歌词位置
const scrollToActiveLyric = () => {
  if (!lyricsContent.value) return
  
  // 查找当前激活的歌词元素
  const activeIndex = parsedLyrics.value.findIndex((_, index) => isActiveLyric(index))
  if (activeIndex === -1) return
  
  // 获取所有歌词行元素
  const lyricElements = lyricsContent.value.children
  if (activeIndex >= 0 && activeIndex < lyricElements.length) {
    const activeElement = lyricElements[activeIndex]
    const container = activeElement.parentElement
    
    // 计算滚动位置，使当前歌词居中
    const containerWidth = container.offsetWidth
    const elementOffsetLeft = activeElement.offsetLeft
    const elementWidth = activeElement.offsetWidth
    const scrollLeft = elementOffsetLeft - (containerWidth / 2) + (elementWidth / 2)
    
    // 平滑滚动到目标位置
    container.scrollTo({
      left: scrollLeft,
      behavior: 'smooth'
    })
  }
}



// 播放音乐 - 通过全局播放器播放
const playMusic = async () => {
  if (currentMusic.value) {
    // 设置当前播放的音乐到localStorage，触发全局播放器
    localStorage.setItem('currentPlayingMusic', JSON.stringify(currentMusic.value));
    
    // 触发storage事件，确保全局播放器能响应变化
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'currentPlayingMusic',
      newValue: JSON.stringify(currentMusic.value),
      oldValue: localStorage.getItem('currentPlayingMusic')
    }));
    
    // 等待一会儿，让全局播放器加载音乐
    await new Promise(resolve => setTimeout(resolve, 100));
    
    // 更新全局播放器状态为播放
    const state = {
      isPlaying: true,
      currentTime: 0,
      duration: currentMusic.value.duration || 0
    };
    localStorage.setItem('globalPlayerState', JSON.stringify(state));
    
    // 触发storage事件，确保全局播放器能响应变化
    window.dispatchEvent(new StorageEvent('storage', {
      key: 'globalPlayerState',
      newValue: JSON.stringify(state),
      oldValue: localStorage.getItem('globalPlayerState')
    }));
    
    // 同时更新本地播放状态
    if (audioPlayer.value) {
      audioPlayer.value.currentTime = 0;
      isPlaying.value = true;
    }
  }
}

// 下载音乐
const downloadMusic = async () => {
  if (currentMusic.value) {
    try {
      // 使用fetch API获取音乐文件
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.value.id}`);
      const blob = await response.blob();
      
      // 创建下载链接
      const url = window.URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = currentMusic.value.filename || `${currentMusic.value.title}.mp3`;
      
      // 添加到DOM，点击并移除
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      
      // 释放URL对象
      window.URL.revokeObjectURL(url);
    } catch (error) {
      console.error('下载音乐失败:', error);
      
      // 如果fetch方法失败，回退到直接链接方法
      const link = document.createElement('a');
      link.href = `${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.value.id}`;
      link.download = currentMusic.value.filename || `${currentMusic.value.title}.mp3`;
      link.target = '_blank'; // 在新标签页中打开，而不是当前页面
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    }
  }
}

// 格式化时长为分秒格式
const formatDuration = (duration) => {
  if (!duration || duration < 0) return '0:00'
  
  const minutes = Math.floor(duration / 60)
  const seconds = Math.floor(duration % 60)
  
  return `${minutes}:${seconds < 10 ? '0' : ''}${seconds}`
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = '/src/assets/default-cover.png'; // 使用默认封面
}

// 初始化
onMounted(async () => {
  const musicId = route.params.id
  if (musicId) {
    await fetchMusicDetail(musicId)
  }
})
</script>

<style scoped>
.music-detail-view {
  max-width: 1200px;
  margin: 40px auto;
  padding: 20px;
}

.detail-container {
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 20px;
  padding: 30px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.content-wrapper {
  display: flex;
  gap: 30px;
}

.detail-section {
  flex: 1;
  min-width: 0; /* 防止flex item溢出 */
}

.cover-section {
  margin-bottom: 25px;
  text-align: center;
}

.music-cover {
  width: 250px;
  height: 250px;
  object-fit: cover;
  border-radius: 15px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  margin: 0 auto;
}

.music-info {
  margin: 25px 0;
  text-align: left;
}

.music-title {
  font-size: 1.8rem;
  color: #5c4b7b;
  margin: 0 0 15px 0;
  font-weight: bold;
}

.music-artist,
.music-album,
.music-duration {
  font-size: 1.1rem;
  color: #6a5acd;
  margin: 8px 0;
  text-align: left;
}

.action-buttons {
  margin-top: 30px;
  display: flex;
  justify-content: center;
  gap: 20px;
}

.play-btn, .download-btn {
  padding: 12px 24px;
  border-radius: 25px;
  border: none;
  font-size: 1rem;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.3s ease;
  min-width: 120px;
}

.play-btn {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  color: white;
  box-shadow: 0 4px 15px rgba(106, 90, 205, 0.4);
}

.play-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(106, 90, 205, 0.6);
}

.download-btn {
  background: linear-gradient(135deg, rgba(76, 175, 80, 0.9), rgba(25, 118, 210, 0.9));
  color: white;
  box-shadow: 0 4px 15px rgba(76, 175, 80, 0.4);
}

.download-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(76, 175, 80, 0.6);
}

/* 歌词显示区域 */
.lyrics-section {
  flex: 1;
  min-width: 0; /* 防止flex item溢出 */
  display: flex;
  flex-direction: column;
}

.lyrics-section h3 {
  color: #6a5acd;
  margin-bottom: 15px;
  font-size: 1.2rem;
  align-self: center;
}

.lyrics-container {
  flex: 1;
  height: 500px;
  padding: 20px;
  background: rgba(255, 255, 255, 0.1);
  border-radius: 15px;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  overflow: hidden;
}

.lyrics-content {
  height: 100%;
  width: 100%;
  display: block;
  position: relative;
}

.lyric-line {
  color: rgba(136, 136, 136, 0.7);
  font-size: 0.8rem;
  padding: 5px 10px;
  text-align: center;
  transition: all 0.5s cubic-bezier(0.68, -0.55, 0.27, 1.55);
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  white-space: nowrap;
  z-index: 1;
  width: auto;
  max-width: 90%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lyric-line.active {
  color: #ffffff;
  font-weight: 700;
  font-size: 1.4rem;
  text-shadow: 0 0 10px rgba(106, 90, 205, 0.8), 0 0 20px rgba(106, 90, 205, 0.6);
  z-index: 10;
  transform: translateX(-50%) scale(1.2);
}

.lyric-line.before {
  transform: translateX(-50%) scale(0.9);
  opacity: 0.6;
}

.lyric-line.after {
  transform: translateX(-50%) scale(0.9);
  opacity: 0.6;
}

.loading {
  text-align: center;
  padding: 40px;
  color: #887bb0;
  font-size: 1.2rem;
}

/* 隐藏的音频元素 */
audio {
  display: none;
}

@media (max-width: 768px) {
  .content-wrapper {
    flex-direction: column;
  }
  
  .detail-container {
    padding: 20px;
    margin: 20px;
  }
  
  .music-cover {
    width: 200px;
    height: 200px;
  }
  
  .music-title {
    font-size: 1.5rem;
  }
  
  .music-info {
    text-align: center;
  }
  
  .music-artist,
  .music-album,
  .music-duration {
    text-align: center;
  }
  
  .action-buttons {
    flex-direction: column;
    align-items: center;
  }
  
  .play-btn, .download-btn {
    width: 80%;
  }
  
  .lyrics-container {
    max-height: 300px;
    padding: 10px;
  }
  
  .lyric-line {
    font-size: 0.8rem;
    padding: 6px 12px;
  }
  
  .lyric-line.active {
    font-size: 0.9rem;
  }
}
</style>