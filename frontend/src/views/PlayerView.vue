<template>
  <div class="music-detail-view">
    <div class="detail-container" v-if="currentMusic">
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
        <button @click="playMusic" class="play-btn">
          播放音乐
        </button>
        <button @click="downloadMusic" class="download-btn">
          下载音乐
        </button>
      </div>
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
    } else {
      console.error('获取音乐详情失败:', data.message)
    }
  } catch (error) {
    console.error('请求音乐详情时出错:', error)
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
  max-width: 800px;
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

.loading {
  text-align: center;
  padding: 40px;
  color: #887bb0;
  font-size: 1.2rem;
}

@media (max-width: 768px) {
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
    padding-left: 0;
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
}
</style>