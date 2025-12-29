<template>
  <header class="search-header">
    <div class="header-content">
      <div class="logo-container">
        <h1 class="logo">Neko云音乐</h1>
      </div>
      <div class="search-container-wrapper">
        <div class="search-container">
          <input
            v-model="searchQuery"
            @input="handleInput"
            @keyup.enter="performSearch"
            type="text"
            placeholder="搜索音乐、艺术家或专辑..."
            class="search-input"
            :disabled="isLoading"
          />
          <button @click="performSearch" class="search-button" :disabled="isLoading">
            <span v-if="isLoading">⏳</span>
            <span v-else>搜索</span>
          </button>
        </div>
        
        <!-- 搜索结果下拉框 -->
        <div v-if="showResults && searchResults && searchResults.length > 0" class="search-results">
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
      </div>
    </div>
    <div class="header-decoration">
      <div class="decoration-dot"></div>
      <div class="decoration-dot"></div>
      <div class="decoration-dot"></div>
    </div>
  </header>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'

const searchQuery = ref('')
const searchResults = ref(null)
const showResults = ref(false)
const isLoading = ref(false)
let debounceTimer = null

// 防抖搜索函数
const debouncedSearch = (query) => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  
  debounceTimer = setTimeout(async () => {
    if (query.trim()) {
      await performSearch()
    } else {
      // 如果输入框为空，清空搜索结果
      searchResults.value = null
      showResults.value = false
    }
  }, 500) // 500ms防抖延迟
}

// 处理输入事件
const handleInput = () => {
  if (searchQuery.value.trim()) {
    debouncedSearch(searchQuery.value)
    showResults.value = true
  } else {
    searchResults.value = null
    showResults.value = false
  }
}

// 执行搜索
const performSearch = async () => {
  if (!searchQuery.value.trim()) return
  
  try {
    isLoading.value = true
    
    // 发送POST搜索请求到后端
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        query: searchQuery.value
      })
    })
    
    const data = await response.json()
    
    // 检查响应是否成功
    if (response.ok) {
      if (data.success && data.results) {
        searchResults.value = data.results
        showResults.value = true
        console.log('搜索成功:', data.results)
      } else {
        // 当后端返回null时，不显示结果框
        searchResults.value = data.results // 这里将是null
        showResults.value = false
        console.log('未找到匹配结果:', data.message)
      }
    } else {
      console.error('搜索失败:', data.message || '未知错误')
      searchResults.value = null
      showResults.value = false
    }
  } catch (error) {
    console.error('搜索请求失败:', error)
    searchResults.value = null
    showResults.value = true
  } finally {
    isLoading.value = false
  }
}

// 选择结果项
const selectResult = (result) => {
  searchQuery.value = `${result.title} - ${result.artist}`
  searchResults.value = null
  showResults.value = false
}

// 点击外部区域隐藏搜索结果
const handleClickOutside = (event) => {
  const searchHeader = event.target.closest('.search-header')
  if (!searchHeader) {
    showResults.value = false
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
})

onUnmounted(() => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  document.removeEventListener('click', handleClickOutside)
})
</script>

<style scoped>
.search-header {
  background: linear-gradient(135deg, #ff9ec0 0%, #ffccf9 100%);
  color: #5c4b7b;
  padding: 1rem 0;
  box-shadow: 0 4px 20px rgba(255, 158, 192, 0.4);
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 2px solid rgba(255, 255, 255, 0.5);
  position: relative;
}

.header-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
}

.logo-container {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo {
  margin: 0;
  font-size: 2rem;
  font-weight: bold;
  flex-shrink: 0;
  background: linear-gradient(45deg, #6a5acd, #ff69b4);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-shadow: 2px 2px 4px rgba(255, 255, 255, 0.3);
}

.search-container-wrapper {
  position: relative;
  display: flex;
  flex-grow: 1;
  max-width: 600px;
  min-width: 250px;
}

.search-container {
  display: flex;
  flex-grow: 1;
  position: relative;
  z-index: 1001; /* 确保搜索框在搜索结果上方 */
}

.search-input {
  flex: 1;
  padding: 14px 20px;
  border: none;
  border-radius: 30px;
  font-size: 1rem;
  outline: none;
  box-shadow: 0 4px 15px rgba(0, 0, 0, 0.1);
  background: rgba(255, 255, 255, 0.9);
  border: 2px solid #ffccf9;
  transition: all 0.3s ease;
  padding-right: 60px; /* 为按钮留出空间 */
}

.search-input:focus {
  border-color: #6a5acd;
  box-shadow: 0 4px 20px rgba(106, 90, 205, 0.3);
}

.search-input:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.search-button {
  position: absolute;
  right: 5px;
  top: 50%;
  transform: translateY(-50%);
  padding: 9px 20px;
  background: linear-gradient(135deg, #6a5acd, #9370db);
  color: white;
  border: none;
  border-radius: 24px;
  cursor: pointer;
  font-size: 1.2rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.4);
  min-width: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.search-button:hover:not(:disabled) {
  background: linear-gradient(135deg, #5c4b7b, #7a5bc0);
  transform: translateY(-50%) scale(1.05);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

.search-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.search-results {
  position: absolute;
  top: 100%;
  left: 0;
  width: 100%;
  background: white;
  border-radius: 10px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.15);
  z-index: 1000;
  max-height: 300px;
  overflow-y: auto;
  margin-top: 5px;
  border: 1px solid #ffccf9;
}

.result-item {
  padding: 12px 16px;
  cursor: pointer;
  transition: background-color 0.2s;
  border-bottom: 1px solid #f0f0f0;
  max-height: 80px;
  max-width: 100%;
  overflow: hidden;
}

.result-item:hover {
  background-color: #fff5f9;
}

.result-item:last-child {
  border-bottom: none;
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
  background: #fffaf0;
}

.no-results-text {
  text-align: center;
  color: #a0a0a0;
  font-style: italic;
}

.header-decoration {
  position: absolute;
  top: 0;
  right: 10%;
  display: flex;
  gap: 10px;
}

.decoration-dot {
  width: 12px;
  height: 12px;
  background: rgba(255, 255, 255, 0.6);
  border-radius: 50%;
  animation: pulse 2s infinite;
}

.decoration-dot:nth-child(2) {
  animation-delay: 0.5s;
}

.decoration-dot:nth-child(3) {
  animation-delay: 1s;
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
}

@keyframes pulse {
  0%, 100% { transform: scale(1); opacity: 0.7; }
  50% { transform: scale(1.2); opacity: 1; }
}

@media (max-width: 768px) {
  .header-content {
    flex-direction: column;
    text-align: center;
  }
  
  .logo-container {
    width: 100%;
    justify-content: center;
  }
  
  .search-container {
    width: 100%;
    max-width: 100%;
  }
  
  .header-decoration {
    display: none;
  }
  
  .search-results {
    min-width: 100%;
  }
}
</style>