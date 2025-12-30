<template>
  <div class="global-player">
    <div class="player-content">
      <!-- 音乐封面 -->
      <div class="cover-container">
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
      <div class="music-info">
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
        
        <div class="control-buttons">
          <button @click="togglePlayPause" class="play-pause-btn" :disabled="!currentMusic">
            <span v-if="isPlaying && currentMusic">⏸️</span>
            <span v-else-if="!currentMusic">▶️</span>
            <span v-else>▶️</span>
          </button>
        </div>
        
        <div class="progress-container">
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
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'

// 从localStorage获取当前播放的音乐信息
const currentMusic = ref(JSON.parse(localStorage.getItem('currentPlayingMusic')) || null)
const audioPlayer = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)

// 播放/暂停控制
const togglePlayPause = () => {
  if (audioPlayer.value && currentMusic.value) {
    if (isPlaying.value) {
      audioPlayer.value.pause()
    } else {
      audioPlayer.value.play().catch(e => console.log('播放被阻止:', e))
    }
    isPlaying.value = !isPlaying.value
    updateGlobalPlayerState()
  }
}

// 音频结束事件
const onAudioEnded = () => {
  isPlaying.value = false
  updateGlobalPlayerState()
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    progress.value = currentTime.value
    updateGlobalPlayerState()
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
    updateGlobalPlayerState()
  }
}

// 进度条变化
const onProgressChange = (event) => {
  const newTime = parseFloat(event.target.value)
  if (audioPlayer.value) {
    audioPlayer.value.currentTime = newTime
    currentTime.value = newTime
    updateGlobalPlayerState()
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

// 格式化时间（秒转分:秒）
const formatTime = (seconds) => {
  if (isNaN(seconds) || seconds < 0) return '0:00'
  
  const min = Math.floor(seconds / 60)
  const sec = Math.floor(seconds % 60)
  return `${min}:${sec < 10 ? '0' : ''}${sec}`
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
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
    })
    
    newPlayer.addEventListener('pause', () => {
      isPlaying.value = false
    })
  }
})

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
        updateGlobalPlayerState();
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
      }
    }
  } else if (e.key === 'globalPlayerState') {
    // 从播放页面接收状态更新
    if (e.newValue) {
      const state = JSON.parse(e.newValue);
      // 如果播放页面正在操作，同步其状态到全局播放器
      if (audioPlayer.value && currentMusic.value) {
        // 同步播放状态
        if (state.isPlaying !== isPlaying.value) {
          if (state.isPlaying) {
            audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
          } else {
            audioPlayer.value.pause();
          }
          isPlaying.value = state.isPlaying;
        }
        
        // 同步时间（但不强制跳转，仅更新显示）
        currentTime.value = state.currentTime;
        duration.value = state.duration;
        progress.value = state.currentTime;
      }
    }
  }
}

onMounted(() => {
  // 监听storage事件，以响应其他标签页的播放变化
  window.addEventListener('storage', handleStorageChange)
  
  // 初始化当前播放音乐
  const storedMusic = localStorage.getItem('currentPlayingMusic')
  if (storedMusic) {
    currentMusic.value = JSON.parse(storedMusic)
  }
})

// 组件卸载时移除事件监听
onUnmounted(() => {
  window.removeEventListener('storage', handleStorageChange)
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
    max-width: 300px;
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