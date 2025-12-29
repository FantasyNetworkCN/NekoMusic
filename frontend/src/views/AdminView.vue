<template>
  <div class="admin-container">
    <div class="admin-header">
      <h2 class="admin-title">管理员面板</h2>
      <p class="admin-subtitle">管理您的音乐平台</p>
      <div class="admin-user-info">
        <span>欢迎，{{ adminInfo.username }}!</span>
        <button @click="logout" class="logout-button">退出登录</button>
      </div>
    </div>
    
    <div class="admin-content">
      <div class="admin-cards">
        <div class="admin-card">
          <div class="card-icon">🎵</div>
          <h3>音乐管理</h3>
          <p>添加、编辑或删除音乐资源</p>
          <button class="card-button">管理音乐</button>
        </div>
        
        <div class="admin-card">
          <div class="card-icon">👥</div>
          <h3>用户管理</h3>
          <p>查看和管理用户信息</p>
          <button class="card-button">管理用户</button>
        </div>
        
        <div class="admin-card">
          <div class="card-icon">📊</div>
          <h3>数据统计</h3>
          <p>查看平台使用统计数据</p>
          <button class="card-button">查看统计</button>
        </div>
        
        <div class="admin-card">
          <div class="card-icon">⚙️</div>
          <h3>系统设置</h3>
          <p>配置平台参数和功能</p>
          <button class="card-button">系统设置</button>
        </div>
      </div>
      
      <div class="admin-stats">
        <h3>平台统计</h3>
        <div class="stats-grid">
          <div class="stat-item">
            <div class="stat-number">0</div>
            <div class="stat-label">总音乐数</div>
          </div>
          <div class="stat-item">
            <div class="stat-number">0</div>
            <div class="stat-label">总用户数</div>
          </div>
          <div class="stat-item">
            <div class="stat-number">0</div>
            <div class="stat-label">今日访问</div>
          </div>
          <div class="stat-item">
            <div class="stat-number">0</div>
            <div class="stat-label">搜索次数</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()
const adminInfo = ref({})

// 初始化管理员信息
onMounted(() => {
  const storedAdminInfo = localStorage.getItem('adminToken')
  if (storedAdminInfo) {
    adminInfo.value = JSON.parse(storedAdminInfo)
  } else {
    // 如果没有存储的管理员信息，重定向到登录页面
    router.push('/admin/login')
  }
})

const logout = () => {
  localStorage.removeItem('adminToken')
  localStorage.removeItem('isAdminLoggedIn')
  router.push('/admin/login')
}
</script>

<style scoped>
.admin-container {
  max-width: 1200px;
  margin: 0 auto;
  padding: 20px;
  text-align: center;
  width: 100%;
}

.admin-header {
  margin-bottom: 40px;
  position: relative;
}

.admin-title {
  color: #6a5acd;
  margin-bottom: 1rem;
  font-size: 2.2rem;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
  background: linear-gradient(45deg, #ff9ec0, #6a5acd, #84ffff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  position: relative;
  z-index: 1;
}

.admin-subtitle {
  color: #887bb0;
  font-size: 1.2rem;
  margin-bottom: 20px;
  position: relative;
  z-index: 1;
}

.admin-user-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 15px;
  padding-top: 15px;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
}

.logout-button {
  background: linear-gradient(135deg, rgba(220, 20, 60, 0.8), rgba(255, 99, 71, 0.8));
  color: white;
  border: none;
  border-radius: 20px;
  padding: 8px 16px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(220, 20, 60, 0.3);
}

.logout-button:hover {
  background: linear-gradient(135deg, rgba(190, 10, 50, 0.9), rgba(235, 79, 51, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(220, 20, 60, 0.5);
}

.admin-content {
  display: flex;
  flex-direction: column;
  gap: 40px;
}

.admin-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 20px;
  margin-bottom: 40px;
}

.admin-card {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 20px;
  padding: 30px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  position: relative;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  transition: transform 0.3s ease, box-shadow 0.3s ease;
  text-align: center;
}

.admin-card::before {
  content: '';
  position: absolute;
  top: -5px;
  left: -5px;
  right: -5px;
  bottom: -5px;
  background: linear-gradient(45deg, #ff9ec0, #6a5acd, #84ffff, #ff9ec0);
  background-size: 400%;
  border-radius: 25px;
  z-index: -1;
  filter: blur(15px);
  opacity: 0.6;
  animation: gradientShift 8s ease infinite;
}

.admin-card:hover {
  transform: translateY(-10px);
  box-shadow: 0 15px 35px rgba(31, 38, 135, 0.5);
}

.card-icon {
  font-size: 3rem;
  margin-bottom: 15px;
}

.admin-card h3 {
  color: #5c4b7b;
  margin: 10px 0;
  font-size: 1.4rem;
}

.admin-card p {
  color: #887bb0;
  margin-bottom: 15px;
  font-size: 0.95rem;
}

.card-button {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(147, 112, 219, 0.8));
  color: white;
  border: none;
  border-radius: 25px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 1rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 15px rgba(106, 90, 205, 0.3);
}

.card-button:hover {
  background: linear-gradient(135deg, rgba(92, 75, 123, 0.9), rgba(122, 91, 192, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 20px rgba(106, 90, 205, 0.5);
}

.admin-stats {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 20px;
  padding: 30px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
}

.admin-stats h3 {
  color: #6a5acd;
  margin-bottom: 20px;
  font-size: 1.6rem;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
  gap: 20px;
}

.stat-item {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 15px;
  padding: 20px;
  text-align: center;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.15);
}

.stat-number {
  font-size: 2rem;
  font-weight: bold;
  color: #6a5acd;
  margin-bottom: 5px;
}

.stat-label {
  color: #887bb0;
  font-size: 0.9rem;
}

@keyframes gradientShift {
  0% {
    background-position: 0% 50%;
  }
  50% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0% 50%;
  }
}
</style>