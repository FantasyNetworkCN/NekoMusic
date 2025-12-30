<template>
  <div class="search-results-view">
    <div class="search-results-container">
      <div v-if="searchResults && searchResults.length > 0" class="results-list">
        <div 
          v-for="result in searchResults" 
          :key="result.id" 
          class="result-item"
          @click="selectResult(result)"
        >
          <img 
            :src="getCoverUrl(result.id)" 
            :alt="result.title"
            class="result-cover"
            @error="handleImageError"
          />
          <div class="result-info">
            <div class="result-title">{{ result.title }}</div>
            <div class="result-artist">作曲：{{ result.artist }}</div>
            <div class="result-album">专辑：{{ result.album || '未知专辑' }}</div>
          </div>
        </div>
      </div>
      <div v-else-if="searchQuery && !searchResults" class="no-results">
        <h3>正在搜索 "{{ searchQuery }}"...</h3>
      </div>
      <div v-else-if="searchQuery && searchResults && searchResults.length === 0" class="no-results">
        <h3>未找到 "{{ searchQuery }}" 的相关结果</h3>
        <p>联系我们反馈补全音乐</p>
        <a href="mailto:support@cnmsb.xin">support@cnmsb.xin</a>
      </div>
      <div v-else class="no-results">
        <h3>请输入搜索关键词</h3>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const route = useRoute()
const searchQuery = ref('')
const searchResults = ref(null)
const router = useRouter()

// 初始化搜索查询
if (route.params.query) {
  searchQuery.value = decodeURIComponent(route.params.query)
}

// 搜索音乐
const searchMusic = async (query) => {
  if (!query.trim()) return
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        query: query
      })
    })
    
    const data = await response.json()
    
    if (response.ok) {
      if (data.success && data.results) {
        searchResults.value = data.results
        console.log('搜索成功:', data.results)
      } else {
        // 当后端返回null时
        searchResults.value = data.results || []
        console.log('未找到匹配结果:', data.message)
      }
    } else {
      console.error('搜索失败:', data.message || '未知错误')
      searchResults.value = []
    }
  } catch (error) {
    console.error('搜索请求失败:', error)
    searchResults.value = []
  }
}

// 选择结果项
const selectResult = (result) => {
  // 点击搜索结果跳转到播放页面
  router.push(`/player/${result.id}`)
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  // 返回新的API端点，通过音乐ID获取封面
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  // 如果图片加载失败，使用后端API的默认图标
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`;
}

// 监听路由参数变化
watch(
  () => route.params.query,
  (newQuery) => {
    if (newQuery) {
      searchQuery.value = decodeURIComponent(newQuery)
      searchMusic(searchQuery.value)
    } else {
      searchResults.value = null
    }
  }
)

onMounted(async () => {
  if (searchQuery.value && searchQuery.value !== 'undefined') {
    await searchMusic(searchQuery.value)
  }
})
</script>

<style scoped>
.search-results-container {
  max-width: 800px;
  margin: 20px auto;
  padding: 20px;
}

.results-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.result-item {
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

.result-item:hover {
  background-color: rgba(255, 255, 255, 0.5);
  box-shadow: inset 0 0 10px rgba(106, 90, 205, 0.3);
}

.result-cover {
  width: 50px;
  height: 50px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.1);
  flex-shrink: 0;
}

.result-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  flex-grow: 1;
  gap: 2px;
}

.result-title {
  font-weight: bold;
  color: #5c4b7b;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 1rem;
}

.result-artist {
  color: #9370db;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.result-album {
  color: #a0a0a0;
  font-size: 0.8rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.no-results {
  text-align: center;
  padding: 40px;
  color: #887bb0;
}

.no-results h3 {
  font-size: 1.5rem;
  color: #6a5acd;
}
</style>