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
        <div class="admin-subpage">
          <h2>用户管理</h2>
          <p>管理平台用户信息，包括查看用户列表、编辑用户权限等操作。</p>
          
          <div class="admin-controls">
            <div class="filter-section">
              <select v-model="filterRole" class="filter-select">
                <option value="">所有角色</option>
                <option value="admin">管理员</option>
                <option value="user">普通用户</option>
              </select>
              <input 
                type="text" 
                v-model="searchQuery" 
                placeholder="搜索用户名或邮箱..." 
                class="search-input"
              />
            </div>
          </div>
          
          <div class="users-list-section">
            <div class="table-container">
              <table class="users-table">
                <thead>
                  <tr>
                    <th>用户名</th>
                    <th>邮箱</th>
                    <th>角色</th>
                    <th>注册时间</th>
                    <th>最后登录</th>
                    <th>状态</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="user in filteredUsers" :key="user.id">
                    <td>{{ user.username }}</td>
                    <td>{{ user.email }}</td>
                    <td>
                      <select 
                        :value="user.role" 
                        @change="changeUserRole(user.id, $event.target.value)"
                        class="role-select"
                      >
                        <option value="user">普通用户</option>
                        <option value="admin">管理员</option>
                      </select>
                    </td>
                    <td>{{ formatDate(user.registerTime) }}</td>
                    <td>{{ user.lastLogin ? formatDate(user.lastLogin) : '从未登录' }}</td>
                    <td>
                      <span :class="['status-badge', user.status]">
                        {{ user.status === 'active' ? '活跃' : '禁用' }}
                      </span>
                    </td>
                    <td>
                      <button class="action-btn edit-btn" @click="editUser(user)">编辑</button>
                      <button 
                        :class="['action-btn', user.status === 'active' ? 'disable-btn' : 'enable-btn']" 
                        @click="toggleUserStatus(user.id, user.status)"
                      >
                        {{ user.status === 'active' ? '禁用' : '启用' }}
                      </button>
                      <button class="action-btn delete-btn" @click="deleteUser(user.id)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
          
          <div class="pagination">
            <button 
              :disabled="currentPage === 1" 
              @click="currentPage--"
              class="page-btn"
            >
              上一页
            </button>
            <span class="page-info">第 {{ currentPage }} 页，共 {{ totalPages }} 页</span>
            <button 
              :disabled="currentPage === totalPages" 
              @click="currentPage++"
              class="page-btn"
            >
              下一页
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed, watch } from 'vue'
import { useRouter } from 'vue-router'
import AdminSidebar from '@/components/AdminSidebar.vue'

const router = useRouter()

// 管理员信息
const adminInfo = ref({})

// 检查管理员登录状态
onMounted(() => {
  const storedToken = localStorage.getItem('adminToken')
  const storedAdminInfo = localStorage.getItem('adminInfo')
  
  if (storedToken && storedAdminInfo) {
    try {
      const parsedInfo = JSON.parse(storedAdminInfo)
      adminInfo.value = parsedInfo
    } catch (e) {
      console.error('解析管理员信息失败:', e)
      router.push('/admin/login')
    }
  } else {
    router.push('/admin/login')
  }
})

// 用户数据
const users = ref([
  { id: 1, username: 'admin', email: 'admin@example.com', role: 'admin', registerTime: new Date(), lastLogin: new Date(), status: 'active' },
  { id: 2, username: 'user1', email: 'user1@example.com', role: 'user', registerTime: new Date(Date.now() - 86400000), lastLogin: new Date(Date.now() - 3600000), status: 'active' },
  { id: 3, username: 'user2', email: 'user2@example.com', role: 'user', registerTime: new Date(Date.now() - 172800000), lastLogin: null, status: 'inactive' },
  { id: 4, username: 'user3', email: 'user3@example.com', role: 'user', registerTime: new Date(Date.now() - 259200000), lastLogin: new Date(Date.now() - 86400000), status: 'active' },
  { id: 5, username: 'moderator', email: 'moderator@example.com', role: 'admin', registerTime: new Date(Date.now() - 345600000), lastLogin: new Date(Date.now() - 1800000), status: 'active' }
])

// 过滤和搜索
const searchQuery = ref('')
const filterRole = ref('')
const currentPage = ref(1)
const usersPerPage = 5

// 计算过滤后的用户列表
const filteredUsers = computed(() => {
  let result = users.value
  
  // 按搜索查询过滤
  if (searchQuery.value) {
    const query = searchQuery.value.toLowerCase()
    result = result.filter(user => 
      user.username.toLowerCase().includes(query) || 
      user.email.toLowerCase().includes(query)
    )
  }
  
  // 按角色过滤
  if (filterRole.value) {
    result = result.filter(user => user.role === filterRole.value)
  }
  
  // 计算分页
  const startIndex = (currentPage.value - 1) * usersPerPage
  const endIndex = startIndex + usersPerPage
  return result.slice(startIndex, endIndex)
})

// 计算总页数
const totalPages = computed(() => {
  let count = users.value.length
  
  // 应用搜索过滤
  if (searchQuery.value) {
    const query = searchQuery.value.toLowerCase()
    count = users.value.filter(user => 
      user.username.toLowerCase().includes(query) || 
      user.email.toLowerCase().includes(query)
    ).length
  }
  
  // 应用角色过滤
  if (filterRole.value) {
    count = users.value.filter(user => user.role === filterRole.value).length
  }
  
  return Math.ceil(count / usersPerPage)
})

// 格式化日期
const formatDate = (date) => {
  if (!date) return '无'
  return new Date(date).toLocaleDateString('zh-CN')
}

// 更改用户角色
const changeUserRole = (userId, newRole) => {
  const user = users.value.find(u => u.id === userId)
  if (user) {
    user.role = newRole
    alert(`用户 ${user.username} 的角色已更改为 ${newRole}`)
  }
}

// 切换用户状态
const toggleUserStatus = (userId, currentStatus) => {
  const user = users.value.find(u => u.id === userId)
  if (user) {
    const newStatus = currentStatus === 'active' ? 'inactive' : 'active'
    if (confirm(`确定要${newStatus === 'active' ? '启用' : '禁用'}用户 ${user.username} 吗？`)) {
      user.status = newStatus
    }
  }
}

// 编辑用户
const editUser = (user) => {
  alert(`编辑用户: ${user.username}`)
  // 这里可以实现编辑功能
}

// 删除用户
const deleteUser = (userId) => {
  const user = users.value.find(u => u.id === userId)
  if (user && confirm(`确定要删除用户 ${user.username} 吗？此操作不可撤销。`)) {
    users.value = users.value.filter(u => u.id !== userId)
    // 如果当前页为空且不是第一页，则转到上一页
    if (filteredUsers.value.length === 0 && currentPage.value > 1) {
      currentPage.value--
    }
  }
}

// 监听页码变化，确保不会超出范围
watch(currentPage, (newPage) => {
  if (newPage < 1) {
    currentPage.value = 1
  } else if (newPage > totalPages.value && totalPages.value > 0) {
    currentPage.value = totalPages.value
  }
})

const logout = () => {
  localStorage.removeItem('adminToken')
  localStorage.removeItem('adminInfo')
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

.admin-subpage {
  padding: 20px;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 15px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.2);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
}

.admin-subpage h2 {
  color: #6a5acd;
  margin: 0 0 20px 0;
  font-size: 1.5rem;
}

.admin-controls {
  margin-bottom: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 15px;
}

.filter-section {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.filter-select, .search-input {
  padding: 8px 12px;
  border: none;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  color: #333;
  font-size: 1rem;
  transition: all 0.3s ease;
}

.filter-select:focus, .search-input:focus {
  outline: none;
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 0 0 2px rgba(106, 90, 205, 0.2);
  background: rgba(255, 255, 255, 0.35);
}

.users-list-section {
  margin-top: 20px;
}

.table-container {
  overflow-x: auto;
}

.users-table {
  width: 100%;
  border-collapse: collapse;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  overflow: hidden;
}

.users-table th,
.users-table td {
  padding: 12px 15px;
  text-align: left;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.users-table th {
  background: rgba(106, 90, 205, 0.3);
  color: #6a5acd;
  font-weight: 600;
}

.users-table tr:last-child td {
  border-bottom: none;
}

.users-table tr:hover {
  background: rgba(106, 90, 205, 0.1);
}

.role-select {
  padding: 5px 10px;
  border: 1px solid rgba(255, 255, 255, 0.3);
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.3);
  color: #333;
}

.status-badge {
  padding: 4px 8px;
  border-radius: 12px;
  font-size: 0.8rem;
  font-weight: bold;
}

.status-badge.active {
  background: rgba(46, 204, 113, 0.2);
  color: #2ecc71;
}

.status-badge.inactive {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.action-btn {
  padding: 6px 12px;
  border: none;
  border-radius: 5px;
  cursor: pointer;
  font-size: 0.8rem;
  margin-right: 5px;
  transition: all 0.3s ease;
}

.edit-btn {
  background: rgba(52, 152, 219, 0.2);
  color: #3498db;
}

.edit-btn:hover {
  background: rgba(52, 152, 219, 0.3);
}

.disable-btn {
  background: rgba(243, 156, 18, 0.2);
  color: #f39c12;
}

.disable-btn:hover {
  background: rgba(243, 156, 18, 0.3);
}

.enable-btn {
  background: rgba(46, 204, 113, 0.2);
  color: #2ecc71;
}

.enable-btn:hover {
  background: rgba(46, 204, 113, 0.3);
}

.delete-btn {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.delete-btn:hover {
  background: rgba(231, 76, 60, 0.3);
}

.pagination {
  margin-top: 20px;
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 15px;
}

.page-btn {
  padding: 8px 15px;
  border: none;
  border-radius: 8px;
  background: rgba(106, 90, 205, 0.2);
  color: #6a5acd;
  cursor: pointer;
  transition: all 0.3s ease;
}

.page-btn:hover:not(:disabled) {
  background: rgba(106, 90, 205, 0.3);
  transform: translateY(-2px);
}

.page-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  transform: none;
}

.page-info {
  color: #887bb0;
  font-size: 0.9rem;
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
}
</style>