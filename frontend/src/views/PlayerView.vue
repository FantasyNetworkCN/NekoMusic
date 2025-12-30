<template>
  <div class="music-player-view">
    <div class="player-container" v-if="currentMusic">
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
        <p class="music-artist">作曲：{{ currentMusic.artist }}</p>
        <p class="music-album">专辑：{{ currentMusic.album || '未知专辑' }}</p>
      </div>
      
      <!-- 音乐播放器 -->
      <div class="player-controls">
        <audio 
          ref="audioPlayer" 
          :src="`${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.id}`" 
          @ended="onAudioEnded"
          @timeupdate="onTimeUpdate"
          @loadedmetadata="onLoadedMetadata"
        />
        
        <div class="progress-container">
          <span class="time">{{ formatTime(currentTime) }}</span>
          <input 
            type="range" 
            class="progress-bar" 
            :value="progress" 
            @input="onProgressChange"
            :max="duration"
          />
          <span class="time">{{ formatTime(duration) }}</span>
        </div>
        
        <div class="control-buttons">
          <button @click="togglePlayPause" class="play-pause-btn">
            <span v-if="isPlaying">⏸️</span>
            <span v-else>▶️</span>
          </button>
        </div>
      </div>
    </div>
    
    <div v-else class="loading">
      <p>加载音乐信息中...</p>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const route = useRoute()
const currentMusic = ref(null)
const audioPlayer = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)

// 获取音乐信息
const fetchMusicInfo = async (musicId) => {
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/info/${musicId}`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json'
      }
    })
    
    const data = await response.json()
    if (data.success) {
      currentMusic.value = data.data
    } else {
      console.error('获取音乐信息失败:', data.message)
    }
  } catch (error) {
    console.error('请求音乐信息时出错:', error)
  }
}

// 播放/暂停控制
const togglePlayPause = () => {
  if (audioPlayer.value) {
    if (isPlaying.value) {
      audioPlayer.value.pause()
    } else {
      audioPlayer.value.play()
    }
    isPlaying.value = !isPlaying.value
  }
}

// 音频结束事件
const onAudioEnded = () => {
  isPlaying.value = false
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    progress.value = currentTime.value
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
  }
}

// 进度条变化
const onProgressChange = (event) => {
  const newTime = parseFloat(event.target.value)
  if (audioPlayer.value) {
    audioPlayer.value.currentTime = newTime
    currentTime.value = newTime
  }
}

// 格式化时间（秒转分:秒）
const formatTime = (seconds) => {
  if (isNaN(seconds)) return '0:00'
  
  const min = Math.floor(seconds / 60)
  const sec = Math.floor(seconds % 60)
  return `${min}:${sec < 10 ? '0' : ''}${sec}`
}

// 格式化时长为分秒格式
const formatDuration = (duration) => {
  if (!duration || duration < 0) return '0:00'
  
  const minutes = Math.floor(duration / 60)
  const seconds = duration % 60
  
  return `${minutes}:${seconds < 10 ? '0' : ''}${seconds}`
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

// 初始化
onMounted(async () => {
  const musicId = route.params.id
  if (musicId) {
    await fetchMusicInfo(musicId)
  }
})
</script>

<style scoped>
.music-player-view {
  max-width: 800px;
  margin: 40px auto;
  padding: 20px;
}

.player-container {
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 20px;
  padding: 30px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  text-align: center;
}

.cover-section {
  margin-bottom: 25px;
}

.music-cover {
  width: 250px;
  height: 250px;
  object-fit: cover;
  border-radius: 15px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.music-info {
  margin: 25px 0;
  text-align: left;
  padding-left: 50px;
}

.music-title {
  font-size: 1.8rem;
  color: #5c4b7b;
  margin: 0 0 10px 0;
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

.player-controls {
  margin-top: 30px;
}

.progress-container {
  display: flex;
  align-items: center;
  gap: 15px;
  margin-bottom: 20px;
}

.time {
  color: #5c4b7b;
  font-weight: bold;
  min-width: 40px;
}

.progress-bar {
  flex: 1;
  height: 6px;
  border-radius: 3px;
  background: rgba(106, 90, 205, 0.3);
  outline: none;
  -webkit-appearance: none;
}

.progress-bar::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: #6a5acd;
  cursor: pointer;
}

.control-buttons {
  display: flex;
  justify-content: center;
  gap: 20px;
}

.play-pause-btn {
  width: 60px;
  height: 60px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  font-size: 1.5rem;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 8px 20px rgba(106, 90, 205, 0.4);
  transition: all 0.3s ease;
}

.play-pause-btn:hover {
  transform: scale(1.05);
  box-shadow: 0 10px 25px rgba(106, 90, 205, 0.6);
}

.loading {
  text-align: center;
  padding: 40px;
  color: #887bb0;
  font-size: 1.2rem;
}

@media (max-width: 768px) {
  .player-container {
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
    padding-left: 0;
    text-align: center;
  }
  
  .music-artist,
  .music-album,
  .music-duration {
    text-align: center;
  }
}
</style>