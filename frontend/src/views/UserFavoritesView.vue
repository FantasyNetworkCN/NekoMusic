<template>
  <div class="favorites-view">
    <div class="favorites-container">
      <div class="favorites-header">
        <div class="header-left">
          <h2>我的收藏</h2>
          <p v-if="favorites.length === 0" class="empty-message">还没有收藏任何音乐</p>
        </div>
        <div class="header-right" v-if="favorites.length > 0">
          <button @click="playAllFavorites" class="play-all-btn" title="播放全部">
            <svg class="play-all-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5v14l11-7z"/>
            </svg>
            播放全部
          </button>
        </div>
      </div>
      
      <div v-if="favorites.length > 0" class="favorites-list">
        <div 
          v-for="music in favorites" 
          :key="music.id" 
          class="favorite-item"
        >
          <img 
            :src="getCoverUrl(music.id)" 
            :alt="music.title"
            class="favorite-cover"
            @error="handleImageError"
          />
          <div class="favorite-info" @click="playMusic(music)">
            <div class="favorite-title">{{ music.title }}</div>
            <div class="favorite-artist">作曲：{{ music.artist }}</div>
            <div class="favorite-album">专辑：{{ music.album || '未知专辑' }}</div>
          </div>
          <div class="favorite-actions">
            <button @click.stop="playMusic(music)" class="play-btn" title="播放">
              ▶️
            </button>
            <button @click.stop="removeFavorite(music.id)" class="remove-btn" title="取消收藏">
              💔
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import { useToast } from 'vue-toastification'
const toast = useToast()

const router = useRouter()
const favorites = ref([])

// 获取用户token
const getToken = () => {
  return localStorage.getItem('userToken')
}

// 检查用户是否登录
const isLoggedIn = () => {
  return !!getToken()
}

// 获取收藏列表
const fetchFavorites = async () => {
  if (!isLoggedIn()) {
    router.push('/login')
    return
  }
  
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites`, {
      method: 'GET',
      headers: {
        'Authorization': token
      }
    })
    
    const data = await response.json()
    if (data.success) {
      favorites.value = data.favorites || []
    } else {
      console.error('获取收藏列表失败:', data.message)
      if (response.status === 401) {
        // Token无效，跳转到登录页
        localStorage.removeItem('userToken')
        localStorage.removeItem('userInfo')
        router.push('/login')
      }
    }
  } catch (error) {
    console.error('获取收藏列表失败:', error)
  }
}

// 播放音乐
const playMusic = async (music) => {
  // 先获取当前播放列表，如果没有则从后端获取
  let playlist = JSON.parse(localStorage.getItem('globalPlaylist') || '[]')
  
  // 检查当前音乐是否已经在播放列表中
  const existingIndex = playlist.findIndex(item => item.id === music.id)
  if (existingIndex === -1) {
    // 如果当前音乐不在播放列表中，则添加到列表中
    playlist.push(music)
    // 保存更新后的播放列表
    localStorage.setItem('globalPlaylist', JSON.stringify(playlist))
    
    // 立即广播播放列表更新事件，确保 GlobalPlayer 组件收到通知
    const playlistEvent = new CustomEvent('playlistUpdated', {
      detail: {
        playlist: playlist
      }
    })
    window.dispatchEvent(playlistEvent)
  }
  
  // 设置当前播放的音乐到localStorage，触发全局播放器
  localStorage.setItem('currentPlayingMusic', JSON.stringify(music))
  
  // 立即更新播放状态为播放，并清零时间（从0.1开始）
  const state = {
    isPlaying: true,
    currentTime: 0.1,
    duration: music.duration || 0
  }
  localStorage.setItem('globalPlayerState', JSON.stringify(state))
  
  // 立即广播播放状态变化
  const event = new CustomEvent('playerStateChange', {
    detail: {
      isPlaying: state.isPlaying,
      currentTime: state.currentTime,
      duration: state.duration,
      currentMusic: music
    }
  })
  window.dispatchEvent(event)
  
  // 立即触发强制播放
  setTimeout(() => {
    window.dispatchEvent(new Event('forcePlay'))
  }, 10)
  
  // 再次确保播放器状态同步
  setTimeout(() => {
    window.dispatchEvent(new Event('forcePlay'))
  }, 100)
}

// 播放全部收藏
const playAllFavorites = () => {
  if (favorites.value.length === 0) {
    toast.warning('收藏列表为空')
    return
  }
  
  // 将整个收藏列表设置为播放列表
  localStorage.setItem('globalPlaylist', JSON.stringify(favorites.value))
  
  // 广播播放列表更新事件
  const playlistEvent = new CustomEvent('playlistUpdated', {
    detail: {
      playlist: favorites.value
    }
  })
  window.dispatchEvent(playlistEvent)
  
  // 播放第一首
  if (favorites.value.length > 0) {
    playMusic(favorites.value[0])
  }
  
  toast.success(`已开始播放全部 ${favorites.value.length} 首收藏音乐`)
}

// 取消收藏
const removeFavorite = async (musicId) => {
  if (!confirm('确定要取消收藏这首音乐吗？')) {
    return
  }
  
  try {
    const token = getToken()
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/user/favorites/${musicId}`, {
      method: 'DELETE',
      headers: {
        'Authorization': token
      }
    })
    
    const data = await response.json()
    if (data.success) {
      // 从列表中移除
      favorites.value = favorites.value.filter(m => m.id !== musicId)
      toast.success('取消收藏成功')
    } else {
      console.error('取消收藏失败:', data.message)
      toast.error('取消收藏失败: ' + data.message)
    }
  } catch (error) {
    console.error('取消收藏失败:', error)
    toast.error('取消收藏失败')
  }
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`
}

onMounted(() => {
  fetchFavorites()
})
</script>

<style scoped>
.favorites-view {
  max-width: 800px;
  margin: 20px auto;
  padding: 20px;
}

.favorites-container {
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 20px;
  padding: 30px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.favorites-header {
  margin-bottom: 30px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 15px;
}

.header-left {
  display: flex;
  flex-direction: column;
  align-items: center;
  flex: 1;
}

.header-right {
  display: flex;
  align-items: center;
}

.favorites-header h2 {
  color: #6a5acd;
  font-size: 2rem;
  margin-bottom: 10px;
}

.empty-message {
  color: #887bb0;
  font-size: 1.2rem;
}

.play-all-btn {
  padding: 10px 20px;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  color: white;
  border: none;
  border-radius: 25px;
  font-size: 1rem;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.3s ease;
  display: flex;
  align-items: center;
  gap: 8px;
  box-shadow: 0 4px 15px rgba(106, 90, 205, 0.4);
}

.play-all-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(106, 90, 205, 0.6);
}

.play-all-icon {
  width: 20px;
  height: 20px;
}

.favorites-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.favorite-item {
  padding: 15px 20px;
  cursor: pointer;
  transition: background-color 0.2s, transform 0.2s;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  display: flex;
  align-items: center;
  gap: 15px;
}

.favorite-item:hover {
  background-color: rgba(255, 255, 255, 0.5);
  box-shadow: inset 0 0 10px rgba(106, 90, 205, 0.3);
}

.favorite-cover {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  flex-shrink: 0;
}

.favorite-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex-grow: 1;
  gap: 2px;
}

.favorite-title {
  font-weight: bold;
  color: #5c4b7b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 1rem;
}

.favorite-artist {
  color: #9370db;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.favorite-album {
  color: #a0a0a0;
  font-size: 0.8rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.favorite-actions {
  display: flex;
  gap: 8px;
  flex-shrink: 0;
  align-items: center;
}

.play-btn, .remove-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.2s ease;
  font-size: 0.8rem;
}

.play-btn:hover, .remove-btn:hover {
  transform: scale(1.1);
  box-shadow: 0 0 8px rgba(106, 90, 205, 0.6);
}

.remove-btn {
  background: linear-gradient(135deg, rgba(255, 107, 107, 0.8), rgba(220, 20, 60, 0.8));
}

.remove-btn:hover {
  box-shadow: 0 0 8px rgba(255, 107, 107, 0.6);
}
</style>