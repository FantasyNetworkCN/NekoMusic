<template>
  <div class="global-player">
    <div class="player-content">
      <!-- 音乐封面 -->
      <div class="cover-container" @click="goToDetails">
        <img 
          v-if="currentMusic"
          :src="getCoverUrl(currentMusic.id)" 
          :alt="currentMusic.title"
          class="music-cover"
          @error="handleImageError"
        />
        <div v-else class="music-cover placeholder-cover">🎵</div>
      </div>
      
      <!-- 音乐信息 -->
      <div class="music-info" @click.stop>
        <div v-if="currentMusic" class="music-title">{{ currentMusic.title }}</div>
        <div v-else class="music-title placeholder-text">请选择音乐播放</div>
        <div v-if="currentMusic" class="music-artist">{{ currentMusic.artist }}</div>
        <div v-else class="music-artist placeholder-text">-</div>
      </div>
      
      <!-- 播放控制 -->
      <div class="player-controls">
        <audio 
          v-if="currentMusic"
          ref="audioPlayer" 
          :src="`${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.id}`" 
          @ended="onAudioEnded"
          @timeupdate="onTimeUpdate"
          @loadedmetadata="onLoadedMetadata"
        />
        
        <div class="control-buttons" @click.stop>
          <button @click="togglePlayPause" class="play-pause-btn" :disabled="!currentMusic">
            <span v-if="isPlaying && currentMusic">⏸️</span>
            <span v-else-if="!currentMusic">▶️</span>
            <span v-else>▶️</span>
          </button>
        </div>
        
        <div class="progress-container" @click.stop>
          <span class="time">{{ formatTime(currentTime) }}</span>
          <input 
            v-if="currentMusic"
            type="range" 
            class="progress-bar" 
            :value="progress" 
            @input="onProgressChange"
            :max="duration"
          />
          <span class="time">{{ formatTime(duration) }}</span>
        </div>
      </div>
      
      <!-- 歌词显示区域 -->
      <div class="lyrics-container" @click.stop>
        <div class="lyrics-content">
          <div class="lyric-line" :class="{ 'active': isCurrentLyric(0), 'active-enter': isCurrentLyric(0) && currentAnimationIndex === 0 }">
            {{ getLyricLine(0) }}
          </div>
          <div class="lyric-line" :class="{ 'active': isCurrentLyric(1), 'active-enter': isCurrentLyric(1) && currentAnimationIndex === 1 }">
            {{ getLyricLine(1) }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const router = useRouter()

// 从localStorage获取当前播放的音乐信息
const currentMusic = ref(JSON.parse(localStorage.getItem('currentPlayingMusic')) || null)
const audioPlayer = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)
const lyrics = ref('')
const parsedLyrics = ref([])
const lyricsContent = ref(null)
const currentAnimationIndex = ref(-1)

// 记录上一个歌词索引
let previousLyricIndex = -1

// 播放/暂停控制
const togglePlayPause = () => {
  if (audioPlayer.value && currentMusic.value) {
    if (isPlaying.value) {
      // 暂停：立即更新状态，然后淡出暂停
      isPlaying.value = false
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      fadeOut(audioPlayer.value)
    } else {
      // 播放：立即更新状态，然后淡入播放
      isPlaying.value = true
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      fadeIn(audioPlayer.value)
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
  
  // 如果音频暂停了，需要先播放
  if (audioElement.paused) {
    audioElement.volume = 0
    audioElement.play().catch(e => console.log('播放被阻止:', e))
  }
  
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
  updateGlobalPlayerState()
  // 广播播放状态变化
  broadcastPlayerStateChange()
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    progress.value = currentTime.value
    updateGlobalPlayerState()
    
    // 检测当前歌词是否发生变化，如果是，则触发动画
    if (parsedLyrics.value.length > 0) {
      let currentLyricIndex = -1
      for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
        const lyric = parsedLyrics.value[i]
        if (currentTime.value >= lyric.time) {
          currentLyricIndex = i
          break
        }
      }
      
      // 如果当前歌词索引发生变化，则触发动画
      if (previousLyricIndex !== currentLyricIndex && currentLyricIndex !== -1) {
        currentAnimationIndex.value = 0 // 为当前歌词行触发动画
        previousLyricIndex = currentLyricIndex
        // 动画结束后清除动画索引
        setTimeout(() => {
          currentAnimationIndex.value = -1
        }, 600)
      }
    }
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
    updateGlobalPlayerState()
    // 广播播放状态变化
    broadcastPlayerStateChange()
    
    // 重置歌词索引
    previousLyricIndex = -1
    
    // 加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
    }
  }
}

// 进度条变化
const onProgressChange = (event) => {
  const newTime = parseFloat(event.target.value)
  if (audioPlayer.value) {
    audioPlayer.value.currentTime = newTime
    currentTime.value = newTime
    updateGlobalPlayerState()
    // 广播播放状态变化
    broadcastPlayerStateChange()
  }
}

// 更新全局播放器状态
const updateGlobalPlayerState = () => {
  const state = {
    isPlaying: isPlaying.value,
    currentTime: currentTime.value,
    duration: duration.value
  };
  localStorage.setItem('globalPlayerState', JSON.stringify(state));
}

// 广播播放器状态变化
const broadcastPlayerStateChange = () => {
  // 创建自定义事件来通知播放状态变化
  const event = new CustomEvent('playerStateChange', {
    detail: {
      isPlaying: isPlaying.value,
      currentTime: currentTime.value,
      duration: duration.value,
      currentMusic: currentMusic.value
    }
  });
  window.dispatchEvent(event);
}

// 格式化时间（秒转分:秒）
const formatTime = (seconds) => {
  if (isNaN(seconds) || seconds < 0) return '0:00'
  
  const min = Math.floor(seconds / 60)
  const sec = Math.floor(seconds % 60)
  return `${min}:${sec < 10 ? '0' : ''}${sec}`
}

// 跳转到音乐详情页面
const goToDetails = () => {
  if (currentMusic.value) {
    // 跳转到音乐详情页面
    router.push(`/detail/${currentMusic.value.id}`)
  }
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
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
    const matches = []
    
    // 收集所有时间标签
    while ((match = timeRegex.exec(line)) !== null) {
      matches.push({
        minutes: parseInt(match[1]),
        seconds: parseInt(match[2]),
        milliseconds: parseInt(match[3]),
        index: match.index
      })
    }
    
    // 提取歌词文本（去除时间标签）
    const text = line.replace(/\[(\d{2}):(\d{2})\.(\d{2,3})\]/g, '').trim()
    
    // 为每个时间标签创建一个歌词项
    for (const timeMatch of matches) {
      // 根据毫秒部分的位数正确计算秒数
      let millisecondsDivisor
      if (timeMatch.milliseconds.toString().length === 2) {
        millisecondsDivisor = 100 // 两位毫秒，如 .25
      } else {
        millisecondsDivisor = 1000 // 三位毫秒，如 .250
      }
      const timeInSeconds = timeMatch.minutes * 60 + timeMatch.seconds + (timeMatch.milliseconds / millisecondsDivisor)
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

// 获取指定索引的歌词行文本
const getLyricLine = (offset) => {
  if (!currentMusic.value || parsedLyrics.value.length === 0) {
    return currentMusic.value ? (offset === 0 ? '暂无歌词' : '') : '请选择音乐播放'
  }
  
  // 查找当前时间点对应的歌词索引
  let currentLyricIndex = -1
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      currentLyricIndex = i
      break
    }
  }
  
  // 根据偏移量返回对应的歌词
  const targetIndex = currentLyricIndex + offset
  if (targetIndex >= 0 && targetIndex < parsedLyrics.value.length) {
    return parsedLyrics.value[targetIndex].text || ''
  }
  
  // 如果超出范围但不是第一行，返回空字符串
  if (offset > 0) {
    return ''
  }
  
  // 如果还没到第一句歌词的时间，显示提示信息
  if (currentLyricIndex === -1 && parsedLyrics.value.length > 0) {
    return '即将开始...'
  }
  
  return '...'
}

// 判断指定偏移量的歌词行是否是当前歌词
const isCurrentLyric = (offset) => {
  if (!currentMusic.value || parsedLyrics.value.length === 0) {
    return offset === 0 && !currentMusic.value // 只有在没有选择音乐时，第一行才"活跃"
  }
  
  // 查找当前时间点对应的歌词索引
  let currentLyricIndex = -1
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      currentLyricIndex = i
      break
    }
  }
  
  // 检查指定偏移量的行是否是当前行
  return (currentLyricIndex + offset) >= 0 && (currentLyricIndex + offset) < parsedLyrics.value.length
}

// 获取当前应该显示的歌词文本 (保留此函数以备后续可能需要)
const getCurrentLyricText = () => {
  if (parsedLyrics.value.length === 0) {
    return '暂无歌词'
  }
  
  // 查找当前时间点对应的歌词
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      return lyric.text || '...'
    }
  }
  
  // 如果还没到第一句歌词的时间，显示提示信息
  return '即将开始...'
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`
}

// 监听音频播放状态
watch(audioPlayer, (newPlayer) => {
  if (newPlayer) {
    newPlayer.addEventListener('play', () => {
      isPlaying.value = true
      updateGlobalPlayerState();
      // 广播播放状态变化
      broadcastPlayerStateChange();
    })
    
    newPlayer.addEventListener('pause', () => {
      isPlaying.value = false
      updateGlobalPlayerState();
      // 广播播放状态变化
      broadcastPlayerStateChange();
    })
  }
}, { immediate: true })

// 监听localStorage变化，响应播放音乐的改变
const handleStorageChange = (e) => {
  if (e.key === 'currentPlayingMusic') {
    // 只有在音乐实际改变时才重置播放器
    const newMusic = e.newValue ? JSON.parse(e.newValue) : null;
    if (newMusic && (!currentMusic.value || newMusic.id !== currentMusic.value.id)) {
      // 音乐改变了，更新当前音乐并重置播放器
      currentMusic.value = newMusic;
      if (audioPlayer.value) {
        audioPlayer.value.load();
        // 重置播放状态
        isPlaying.value = false;
        currentTime.value = 0;
        progress.value = 0;
        duration.value = 0;
      }
      // 加载新音乐的歌词
      if (newMusic) {
        loadLyrics(newMusic.id)
      } else {
        lyrics.value = ''
        parsedLyrics.value = []
      }
    } else if (!e.newValue) {
      // 没有音乐了，暂停播放器
      currentMusic.value = null;
      if (audioPlayer.value) {
        audioPlayer.value.pause();
        currentTime.value = 0;
        progress.value = 0;
        isPlaying.value = false;
        duration.value = 0;
        updateGlobalPlayerState();
        // 广播播放状态变化
        broadcastPlayerStateChange();
      }
      // 清空歌词
      lyrics.value = ''
      parsedLyrics.value = []
    }
  } else if (e.key === 'globalPlayerState') {
    // 从播放页面接收状态更新
    if (e.newValue) {
      const state = JSON.parse(e.newValue);
      // 更新播放器状态，无论audio元素是否准备好
      isPlaying.value = state.isPlaying;
      currentTime.value = state.currentTime;
      duration.value = state.duration;
      progress.value = state.currentTime;
      
      // 如果有audio元素则同步操作
      if (audioPlayer.value && currentMusic.value) {
        // 等待音频加载完成再执行操作
        const playWhenReady = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying) {
              audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
            } else {
              audioPlayer.value.pause();
            }
          }
        };
        
        if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
          playWhenReady();
        } else {
          audioPlayer.value.addEventListener('loadeddata', playWhenReady, { once: true });
        }
        updateGlobalPlayerState();
      } else if (currentMusic.value) {
        // 如果audio元素还没准备好，等待并执行操作
        const handleMetadata = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying) {
              audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
            } else {
              audioPlayer.value.pause();
            }
          }
        };
        
        audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
      }
    }
  }
}

// 处理自定义播放状态变化事件
const handlePlayerStateChange = (e) => {
  const state = e.detail;
  // 更新播放器状态，无论audio元素是否准备好
  isPlaying.value = state.isPlaying;
  currentTime.value = state.currentTime;
  duration.value = state.duration;
  progress.value = state.currentTime;

  // 如果有audio元素则同步操作
  if (audioPlayer.value && currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    // 等待音频加载完成再执行操作
    const playWhenReady = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying) {
          audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
        } else {
          audioPlayer.value.pause();
        }
      }
    };

    if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
      playWhenReady();
    } else {
      audioPlayer.value.addEventListener('loadeddata', playWhenReady, { once: true });
    }
    updateGlobalPlayerState();
  } else if (currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    // 如果audio元素还没准备好，等待并执行操作
    const handleMetadata = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying) {
          audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
        } else {
          audioPlayer.value.pause();
        }
      }
    };

    audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
  }
}

onMounted(() => {
  // 监听storage事件，以响应其他标签页的播放变化
  window.addEventListener('storage', handleStorageChange)
  // 监听自定义事件，以响应播放页面的状态变化
  window.addEventListener('playerStateChange', handlePlayerStateChange)
  
  // 初始化当前播放音乐
  const storedMusic = localStorage.getItem('currentPlayingMusic')
  if (storedMusic) {
    currentMusic.value = JSON.parse(storedMusic)
    
    // 初始化时加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
    }
  }
  
  // 初始化时从localStorage获取播放状态
  const storedState = localStorage.getItem('globalPlayerState');
  if (storedState) {
    const state = JSON.parse(storedState);
    currentTime.value = state.currentTime;
    duration.value = state.duration;
    progress.value = state.currentTime;
    
    // 如果全局播放器应该正在播放，则同步播放状态
    if (state.isPlaying && audioPlayer.value && currentMusic.value) {
      audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
      isPlaying.value = true;
    }
  }
})

// 组件卸载时移除事件监听
onUnmounted(() => {
  window.removeEventListener('storage', handleStorageChange)
  window.removeEventListener('playerStateChange', handlePlayerStateChange)
})
</script>

<style scoped>
.global-player {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 80px;
  width: 100%;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255, 255, 255, 0.3);
  padding: 10px 20px;
  box-shadow: 0 -2px 20px rgba(0, 0, 0, 0.1);
  z-index: 1000;
  transition: transform 0.3s ease;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 15px;
}

.player-content {
  display: flex;
  flex-direction: row;
  align-items: center;
  height: 100%;
  width: 100%;
  gap: 15px;
}

.cover-container {
  flex-shrink: 0;
}

.music-cover {
  width: 60px;
  height: 60px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.placeholder-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #6a5acd, #9370db);
  color: white;
  font-size: 1.2rem;
}

.music-info {
  flex-grow: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 200px;
}

.music-title {
  font-weight: bold;
  color: #5c4b7b;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}

.placeholder-text {
  color: #aaa;
}

.music-artist {
  color: #9370db;
  font-size: 0.75rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.player-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-grow: 1;
  min-width: 200px;
  max-width: 400px;
}

.control-buttons {
  display: flex;
  align-items: center;
}

.play-pause-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  color: white;
  font-size: 1.2rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(106, 90, 205, 0.4);
  transition: all 0.3s ease;
}

.play-pause-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.play-pause-btn:hover:not(:disabled) {
  transform: scale(1.05);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.6);
}

.progress-container {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-grow: 1;
  min-width: 150px;
}

.time {
  color: #5c4b7b;
  font-size: 0.8rem;
  font-weight: bold;
  min-width: 40px;
  text-align: center;
}

.progress-bar {
  flex: 1;
  height: 5px;
  border-radius: 3px;
  background: rgba(106, 90, 205, 0.2);
  outline: none;
  -webkit-appearance: none;
}

.progress-bar::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #6a5acd;
  cursor: pointer;
}

/* 歌词显示区域 */
.lyrics-container {
  flex: 1;
  min-width: 150px;
  max-width: 350px;
  height: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  padding-left: 20px;
  border-left: 1px solid #eee;
  margin-left: 10px;
}

.lyrics-content {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  gap: 6px;
  overflow: hidden;
  position: relative;
}

.lyric-line {
  color: #888;
  font-size: 0.85rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  width: 100%;
  text-align: left;
  line-height: 1.5;
  opacity: 0.5;
  position: relative;
  transition: opacity 0.4s ease;
}

.lyric-line.active {
  color: #6a5acd;
  font-weight: 700;
  font-size: 1rem;
  opacity: 1;
  text-shadow: 0 0 10px rgba(106, 90, 205, 0.5);
}

/* 滚动进入动画 */
@keyframes scrollIn {
  0% {
    transform: translateX(20px);
    opacity: 0;
  }
  100% {
    transform: translateX(0);
    opacity: 1;
  }
}

/* 当歌词变成活动状态时的滚动动画 */
.lyric-line.active-enter {
  animation: scrollIn 0.6s ease-out;
}

/* 隐藏audio元素 */
audio {
  display: none;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .global-player {
    height: 70px;
    padding: 8px 15px;
  }
  
  .player-content {
    gap: 10px;
  }
  
  .music-cover {
    width: 50px;
    height: 50px;
  }
  
  .music-info {
    max-width: 120px;
  }
  
  .music-title {
    font-size: 0.8rem;
  }
  
  .music-artist {
    font-size: 0.7rem;
  }
  
  .player-controls {
    max-width: 200px;
  }
  
  .lyrics-container {
    display: none; /* 在小屏幕上隐藏歌词 */
  }
  
  .play-pause-btn {
    width: 35px;
    height: 35px;
    font-size: 1rem;
  }
  
  .time {
    font-size: 0.7rem;
    min-width: 30px;
  }
  
  .progress-bar {
    height: 4px;
  }
  
  .progress-bar::-webkit-slider-thumb {
    width: 12px;
    height: 12px;
  }
}
</style>