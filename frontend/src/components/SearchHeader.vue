<template>
  <header class="search-header">
    <div class="header-content">
      <div class="logo-container">
        <h1 class="logo">NekoMusic</h1>
        <div class="logo-decoration">🐱</div>
      </div>
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
    <div class="header-decoration">
      <div class="decoration-dot"></div>
      <div class="decoration-dot"></div>
      <div class="decoration-dot"></div>
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
  background: linear-gradient(135deg, #ff9ec0 0%, #ffccf9 100%);
  color: #5c4b7b;
  padding: 1rem 0;
  box-shadow: 0 4px 20px rgba(255, 158, 192, 0.4);
  position: sticky;
  top: 0;
  z-index: 100;
  border-bottom: 2px solid rgba(255, 255, 255, 0.5);
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

.logo-decoration {
  font-size: 1.8rem;
  animation: bounce 2s infinite;
}

.search-container {
  display: flex;
  flex-grow: 1;
  max-width: 600px;
  min-width: 250px;
  position: relative;
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
}

.search-input:focus {
  border-color: #6a5acd;
  box-shadow: 0 4px 20px rgba(106, 90, 205, 0.3);
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

.search-button:hover {
  background: linear-gradient(135deg, #5c4b7b, #7a5bc0);
  transform: translateY(-50%) scale(1.05);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
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
}
</style>