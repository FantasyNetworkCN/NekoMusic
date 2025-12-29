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
          <div class="result-info">
            <div class="result-title">{{ result.title }}</div>
            <div class="result-artist">{{ result.artist }}</div>
            <div class="result-album" v-if="result.album">{{ result.album }}</div>
          </div>
        </div>
      </div>
      <div v-else-if="searchQuery && !searchResults" class="no-results">
        <h3>正在搜索 "{{ searchQuery }}"...</h3>
      </div>
      <div v-else-if="searchQuery && searchResults && searchResults.length === 0" class="no-results">
        <h3>未找到 "{{ searchQuery }}" 的相关结果</h3>
      </div>
      <div v-else class="no-results">
        <h3>请输入搜索关键词</h3>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const route = useRoute()
const searchQuery = ref(decodeURIComponent(route.params.query || ''))
const searchResults = ref(null)

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
  // 可以根据需要实现选择结果后的逻辑
  console.log('选择结果:', result)
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
}

.result-item:hover {
  background-color: rgba(255, 255, 255, 0.5);
  box-shadow: inset 0 0 10px rgba(106, 90, 205, 0.3);
}

.result-info {
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.result-title {
  font-weight: bold;
  color: #5c4b7b;
  margin-bottom: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  margin-top: 2px;
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