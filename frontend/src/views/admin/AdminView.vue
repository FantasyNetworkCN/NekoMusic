<template>
  <div class="admin-layout">
    <AdminSidebar />
    
    <div class="admin-main-content">
      <div class="admin-header">
        <div class="admin-user-info">
          <span>欢迎，{{ adminInfo.username || '管理员' }}!</span>
          <button @click="logout" class="logout-button">退出登录</button>
        </div>
      </div>
      
      <div class="admin-content-wrapper">
        <div class="admin-dashboard">
          <div class="admin-header-section">
            <h2 class="admin-title">管理中心</h2>
            <p class="admin-subtitle">平台数据统计与管理</p>
          </div>
          

            <!-- 统计概览 -->
            <div v-if="activeTab === 'stats'" class="tab-panel">
              <div class="stats-summary">
                <div class="stat-card">
                  <div class="stat-icon">🎵</div>
                  <div class="stat-info">
                    <div class="stat-number">{{ stats.totalMusic }}</div>
                    <div class="stat-label">总音乐数</div>
                  </div>
                </div>
                
                <div class="stat-card">
                  <div class="stat-icon">👥</div>
                  <div class="stat-info">
                    <div class="stat-number">{{ stats.totalUsers }}</div>
                    <div class="stat-label">总用户数</div>
                  </div>
                </div>
              </div>
              
              <div class="chart-section">
                <h3>数据趋势图</h3>
                <div class="chart-placeholder">
                  <p>图表显示区域</p>
                  <p>这里会显示平台数据的趋势图表</p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
</template>

<script setup>
import { ref, onMounted, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'
import AdminSidebar from '@/components/AdminSidebar.vue'

const router = useRouter()
const stats = ref({
  totalMusic: 0,
  totalUsers: 0
})

// 管理员信息
const adminInfo = ref({})
const activeTab = ref('stats')

// 初始化管理员信息
onMounted(() => {
  const storedAdminInfo = localStorage.getItem('adminToken')
  if (storedAdminInfo) {
    try {
      const parsedInfo = JSON.parse(storedAdminInfo)
      adminInfo.value = parsedInfo
    } catch (e) {
      console.error('解析管理员信息失败:', e)
    }
    fetchStats()
  } else {
    // 如果没有存储的管理员信息，重定向到登录页面
    router.push('/admin/login')
  }
})

// 组件激活时（例如路由切换回来时）重新获取数据
onActivated(() => {
  const storedAdminInfo = localStorage.getItem('adminToken')
  if (storedAdminInfo) {
    fetchStats()
  }
})

const fetchStats = async () => {
  try {
    const storedAdminInfo = localStorage.getItem('adminToken')
    const adminData = JSON.parse(storedAdminInfo || '{}')
    
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/admin/stats`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': adminData.username || '' // 发送管理员用户名进行验证
      }
    })
    
    const data = await response.json()
    
    if (response.ok && data.success) {
      // 只更新后端返回的实际数据（totalMusic 和 totalUsers）
      stats.value = {
        totalMusic: data.data.totalMusic || 0,
        totalUsers: data.data.totalUsers || 0
      }
    } else {
      console.error('获取统计数据失败:', data.message)
      // 使用默认值
      stats.value = {
        totalMusic: 0,
        totalUsers: 0
      }
    }
  } catch (error) {
    console.error('获取统计数据时发生错误:', error)
    // 使用默认值
    stats.value = {
      totalMusic: 0,
      totalUsers: 0
    }
  }
}

const goTo = (path) => {
  router.push(path)
}

const logout = () => {
  localStorage.removeItem('adminToken')
  localStorage.removeItem('isAdminLoggedIn')
  router.push('/admin/login')
}
</script>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: linear-gradient(135deg, #f5f7fa 0%, #e4edf5 100%);
}

.admin-main-content {
  flex: 1;
  margin-left: 250px; /* 侧边栏宽度 */
  padding: 20px;
  transition: margin-left 0.3s ease;
  min-height: calc(100vh - 40px);
  display: flex;
  flex-direction: column;
}

.admin-header {
  padding: 20px;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 15px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.2);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  margin-bottom: 20px;
  flex-shrink: 0; /* 防止头部被压缩 */
}

.admin-user-info {
  display: flex;
  justify-content: space-between;
  align-items: center;
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

.admin-content-wrapper {
  flex: 1; /* 让内容区域占据剩余空间 */
  padding: 0 20px;
  min-height: 0; /* 允许内容区域收缩 */
  overflow: auto; /* 如果内容过多，允许滚动 */
}

.admin-dashboard {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.admin-header-section {
  margin-bottom: 30px;
}

.admin-title {
  color: #6a5acd;
  margin: 0 0 10px 0;
  font-size: 1.8rem;
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
  font-size: 1rem;
  margin: 0;
  position: relative;
  z-index: 1;
}

.stats-summary {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.stat-card {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 15px;
  padding: 20px;
  display: flex;
  align-items: center;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.2);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  transition: transform 0.3s ease;
}

.stat-card:hover {
  transform: translateY(-5px);
}

.stat-icon {
  font-size: 2rem;
  margin-right: 15px;
}

.stat-info {
  flex: 1;
}

.stat-number {
  font-size: 1.8rem;
  font-weight: bold;
  color: #6a5acd;
  margin-bottom: 5px;
}

.stat-label {
  color: #887bb0;
  font-size: 0.9rem;
}

.chart-section {
  background: rgba(255, 255, 255, 0.2);
  border-radius: 15px;
  padding: 25px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.2);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.chart-section h3 {
  color: #6a5acd;
  margin: 0 0 20px 0;
  font-size: 1.3rem;
}

.chart-placeholder {
  text-align: center;
  padding: 40px;
  color: #887bb0;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
}

.section-content {
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.content-placeholder {
  padding: 30px;
  color: #887bb0;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  text-align: center;
  margin-top: 10px;
}

.action-buttons {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.action-btn {
  padding: 10px 20px;
  border: none;
  border-radius: 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  text-decoration: none;
  display: inline-block;
}

.action-btn.primary {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.3);
}

.action-btn.primary:hover {
  background: linear-gradient(135deg, rgba(86, 70, 185, 0.9), rgba(118, 23, 206, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

/* 响应式设计 */
@media (max-width: 768px) {
  .admin-main-content {
    margin-left: 0;
    padding: 10px;
  }
  
  .admin-layout {
    flex-direction: column;
  }
  
  .stats-summary {
    grid-template-columns: 1fr;
  }
  
  .tab-button {
    padding: 10px 15px;
    font-size: 0.85rem;
  }
}
</style>