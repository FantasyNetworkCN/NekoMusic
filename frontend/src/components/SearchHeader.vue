<template>
  <header class="search-header">
    <div class="header-content">
      <h1 class="logo">NekoMusic</h1>
      <div class="search-container">
        <input
          v-model="searchQuery"
          @keyup.enter="performSearch"
          type="text"
          placeholder="搜索音乐、艺术家或专辑..."
          class="search-input"
        />
        <button @click="performSearch" class="search-button">
          <span>🔍</span>
        </button>
      </div>
    </div>
  </header>
</template>

<script setup>
import { ref } from 'vue'
import API_CONFIG from '@/config/apiConfig.js'

const searchQuery = ref('')

const performSearch = async () => {
  if (!searchQuery.value.trim()) return
  
  try {
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
    if (response.ok && data.success) {
      console.log('搜索成功:', data.results)
      // 这里可以将搜索结果传递给其他组件
    } else {
      console.error('搜索失败:', data.message || '未知错误')
    }
  } catch (error) {
    console.error('搜索请求失败:', error)
  }
}
</script>

<style scoped>
.search-header {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 1rem 0;
  box-shadow: 0 2px 10px rgba(0, 0, 0, 0.1);
  position: sticky;
  top: 0;
  z-index: 100;
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

.logo {
  margin: 0;
  font-size: 1.8rem;
  font-weight: bold;
  flex-shrink: 0;
}

.search-container {
  display: flex;
  flex-grow: 1;
  max-width: 600px;
  min-width: 250px;
}

.search-input {
  flex: 1;
  padding: 12px 16px;
  border: none;
  border-radius: 24px 0 0 24px;
  font-size: 1rem;
  outline: none;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2);
}

.search-button {
  padding: 12px 20px;
  background: #5c6bc0;
  color: white;
  border: none;
  border-radius: 0 24px 24px 0;
  cursor: pointer;
  font-size: 1.2rem;
  transition: background-color 0.3s;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2);
}

.search-button:hover {
  background: #3f51b5;
}

@media (max-width: 768px) {
  .header-content {
    flex-direction: column;
    text-align: center;
  }
  
  .logo {
    width: 100%;
  }
  
  .search-container {
    width: 100%;
    max-width: 100%;
  }
}
</style>