<template>
  <header class="search-header">
    <div class="header-content">
      <div class="logo-container">
        <h1 class="logo" @click="goHome">Neko云音乐</h1>
      </div>
      <div class="search-container-wrapper">
        <div class="search-container">
          <input
            v-model="searchQuery"
            @input="handleInput"
            @keyup.enter="performSearch"
            type="text"
            placeholder="搜索音乐、艺术家或歌词"
            class="search-input"
            :disabled="isLoading"
          />
<!--          <button @click="performSearch" class="search-button" :disabled="isLoading">
            <span v-if="isLoading">⏳</span>
            <span v-else>搜索</span>
          </button>-->
        </div>
        
        <!-- 搜索结果下拉框 -->
        <div v-if="showResults && searchResults && searchResults.length > 0" class="search-results">
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
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const router = useRouter()
const searchQuery = ref('')
const searchResults = ref(null)
const showResults = ref(false)
const isLoading = ref(false)
let debounceTimer = null

// 防抖搜索函数 - 只获取结果，不跳转页面
const debouncedSearch = (query) => {
  if (debounceTimer) {
    clearTimeout(debounceTimer)
  }
  
  debounceTimer = setTimeout(async () => {
    if (query.trim()) {
      await fetchSearchResults(query)
    } else {
      // 如果输入框为空，清空搜索结果
      searchResults.value = null
      showResults.value = false
    }
    
    // 确保输入框保持焦点
    setTimeout(() => {
      const inputElement = document.querySelector('.search-input')
      if (inputElement && document.activeElement !== inputElement) {
        inputElement.focus()
      }
    }, 0)
  }, 500) // 500ms防抖延迟
}

// 获取搜索结果，不跳转页面
const fetchSearchResults = async (query) => {
  try {
    isLoading.value = true
    
    // 发送POST搜索请求到后端
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
    
    // 检查响应是否成功
    if (response.ok) {
      if (data.success && data.results) {
        searchResults.value = data.results
        showResults.value = true
        console.log('搜索成功:', data.results)
      } else {
        // 当后端返回null时，不显示结果框
        searchResults.value = null
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
    showResults.value = false
  } finally {
    isLoading.value = false
  }
}

// 跳转到首页
const goHome = () => {
  router.push('/')
  searchQuery.value = ''
  searchResults.value = null
  showResults.value = false
}

// 处理输入事件 - 触发防抖搜索
const handleInput = () => {
  if (searchQuery.value.trim()) {
    debouncedSearch(searchQuery.value)
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
        searchResults.value = null
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
    showResults.value = false
  } finally {
    isLoading.value = false
    
    // 只有在回车时才跳转页面
    router.push(`/search/${encodeURIComponent(searchQuery.value)}`)
    
    // 确保输入框保持焦点
    setTimeout(() => {
      const inputElement = document.querySelector('.search-input')
      if (inputElement && document.activeElement !== inputElement) {
        inputElement.focus()
      }
    }, 0)
  }
}

// 选择结果项
const selectResult = (result) => {
  searchQuery.value = `${result.title} - ${result.artist}`
  searchResults.value = null
  showResults.value = false
  
  // 不跳转页面，只更新搜索框内容
  // 跳转应该只在用户按回车键时发生
  
  // 确保输入框保持焦点
  setTimeout(() => {
    const inputElement = document.querySelector('.search-input')
    if (inputElement) {
      inputElement.focus()
    }
  }, 0)
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
  background: rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  color: #5c4b7b;
  padding: 1rem 0;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  position: sticky;
  top: 0;
  z-index: 100;
  border: 1px solid rgba(255, 255, 255, 0.18);
  position: relative;
  border-radius: 20px;
  margin: 10px auto;
  max-width: 1200px;
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
  background: linear-gradient(45deg, #6a5acd, #ff69b4, #84ffff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  text-shadow: 2px 2px 4px rgba(255, 255, 255, 0.3);
  position: relative;
  z-index: 2;
  cursor: pointer;
  transition: transform 0.2s ease, text-shadow 0.2s ease;
}

.logo:hover {
  transform: scale(1.05);
  text-shadow: 2px 2px 8px rgba(106, 90, 205, 0.5);
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
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  background: rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  transition: all 0.3s ease;
  padding-right: 60px; /* 为按钮留出空间 */
  color: #333;
}

.search-input::placeholder {
  color: rgba(92, 75, 123, 0.6);
}

.search-input:focus {
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 8px 32px rgba(106, 90, 205, 0.3);
  background: rgba(255, 255, 255, 0.35);
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
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(147, 112, 219, 0.8));
  color: white;
  border: none;
  border-radius: 24px;
  cursor: pointer;
  font-size: 1.2rem;
  transition: all 0.3s ease;
  box-shadow: 0 8px 32px rgba(106, 90, 205, 0.3);
  min-width: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  backdrop-filter: blur(5px);
  -webkit-backdrop-filter: blur(5px);
}

.search-button:hover:not(:disabled) {
  background: linear-gradient(135deg, rgba(92, 75, 123, 0.9), rgba(122, 91, 192, 0.9));
  transform: translateY(-50%) scale(1.05);
  box-shadow: 0 10px 30px rgba(106, 90, 205, 0.5);
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
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 15px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  z-index: 1000;
  max-height: 300px;
  overflow-y: auto;
  margin-top: 5px;
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.result-item {
  padding: 12px 16px;
  cursor: pointer;
  transition: background-color 0.2s, transform 0.2s;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
  max-height: 80px;
  max-width: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  gap: 12px;
}

.result-item:hover {
  background-color: rgba(255, 255, 255, 0.5);
  box-shadow: inset 0 0 10px rgba(106, 90, 205, 0.3);
}

.result-item:last-child {
  border-bottom: none;
}

.result-cover {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
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
  font-size: 0.9rem;
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
  
  .search-header {
    margin: 10px;
  }
}
</style>