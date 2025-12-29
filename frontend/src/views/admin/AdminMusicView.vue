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
          <h2>音乐管理</h2>
          <p>管理平台音乐资源，包括添加、编辑、删除音乐等操作。</p>
          
          <div class="admin-controls">
            <button class="add-btn" @click="showAddForm = !showAddForm">
              {{ showAddForm ? '取消添加' : '添加音乐' }}
            </button>
          </div>
          
          <div v-if="showAddForm" class="add-music-form">
            <h3>添加新音乐</h3>
            <div class="form-grid">
              <div class="form-group">
                <label>音乐名称</label>
                <input type="text" v-model="newMusic.title" placeholder="请输入音乐名称" />
              </div>
              <div class="form-group">
                <label>艺术家</label>
                <input type="text" v-model="newMusic.artist" placeholder="请输入艺术家" />
              </div>
              <div class="form-group">
                <label>专辑</label>
                <input type="text" v-model="newMusic.album" placeholder="请输入专辑" />
              </div>
              <div class="form-group">
                <label>音乐文件</label>
                <input type="file" @change="handleFileUpload" accept="audio/*" />
              </div>
            </div>
            <div class="form-actions">
              <button class="primary-btn" @click="addMusic">添加音乐</button>
            </div>
          </div>
          
          <div class="music-list-section">
            <h3>音乐列表</h3>
            <div class="search-filter">
              <input 
                type="text" 
                v-model="searchQuery" 
                placeholder="搜索音乐或艺术家..." 
                class="search-input"
              />
            </div>
            
            <div class="table-container">
              <table class="music-table">
                <thead>
                  <tr>
                    <th>音乐名称</th>
                    <th>艺术家</th>
                    <th>专辑</th>
                    <th>上传时间</th>
                    <th>操作</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="music in filteredMusicList" :key="music.id">
                    <td>{{ music.title }}</td>
                    <td>{{ music.artist }}</td>
                    <td>{{ music.album }}</td>
                    <td>{{ formatDate(music.uploadTime) }}</td>
                    <td>
                      <button class="action-btn edit-btn" @click="editMusic(music)">编辑</button>
                      <button class="action-btn delete-btn" @click="deleteMusic(music.id)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AdminSidebar from '@/components/AdminSidebar.vue'

const router = useRouter()

// 管理员信息
const adminInfo = ref({})

// 检查管理员登录状态
onMounted(() => {
  const storedAdminInfo = localStorage.getItem('adminToken')
  if (storedAdminInfo) {
    try {
      const parsedInfo = JSON.parse(storedAdminInfo)
      adminInfo.value = parsedInfo
    } catch (e) {
      console.error('解析管理员信息失败:', e)
    }
  } else {
    router.push('/admin/login')
  }
})

// 音乐数据
const musicList = ref([
  { id: 1, title: '音乐标题1', artist: '艺术家1', album: '专辑1', uploadTime: new Date() },
  { id: 2, title: '音乐标题2', artist: '艺术家2', album: '专辑2', uploadTime: new Date(Date.now() - 86400000) },
  { id: 3, title: '音乐标题3', artist: '艺术家3', album: '专辑3', uploadTime: new Date(Date.now() - 172800000) }
])

// 表单状态
const showAddForm = ref(false)
const newMusic = ref({
  title: '',
  artist: '',
  album: '',
  file: null
})
const searchQuery = ref('')

// 计算属性：过滤音乐列表
const filteredMusicList = ref(musicList.value)

// 文件上传处理
const handleFileUpload = (event) => {
  newMusic.value.file = event.target.files[0]
}

// 添加音乐
const addMusic = () => {
  if (!newMusic.value.title || !newMusic.value.artist) {
    alert('请填写音乐名称和艺术家')
    return
  }
  
  const musicToAdd = {
    id: musicList.value.length + 1,
    title: newMusic.value.title,
    artist: newMusic.value.artist,
    album: newMusic.value.album || '未知专辑',
    uploadTime: new Date()
  }
  
  musicList.value.unshift(musicToAdd)
  filteredMusicList.value = musicList.value
  resetForm()
}

// 重置表单
const resetForm = () => {
  newMusic.value = {
    title: '',
    artist: '',
    album: '',
    file: null
  }
  showAddForm.value = false
}

// 编辑音乐
const editMusic = (music) => {
  alert(`编辑音乐: ${music.title}`)
  // 这里可以实现编辑功能
}

// 删除音乐
const deleteMusic = (id) => {
  if (confirm('确定要删除这首音乐吗？')) {
    musicList.value = musicList.value.filter(music => music.id !== id)
    filteredMusicList.value = musicList.value
  }
}

// 格式化日期
const formatDate = (date) => {
  return new Date(date).toLocaleDateString('zh-CN')
}

// 监听搜索查询变化
const updateSearchResults = () => {
  if (!searchQuery.value.trim()) {
    filteredMusicList.value = musicList.value
  } else {
    const query = searchQuery.value.toLowerCase()
    filteredMusicList.value = musicList.value.filter(music => 
      music.title.toLowerCase().includes(query) || 
      music.artist.toLowerCase().includes(query)
    )
  }
}

// 监听搜索查询变化
searchQuery.value = ''
updateSearchResults()

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
  justify-content: flex-end;
}

.add-btn {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  border: none;
  border-radius: 20px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.3);
}

.add-btn:hover {
  background: linear-gradient(135deg, rgba(86, 70, 185, 0.9), rgba(118, 23, 206, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

.add-music-form {
  background: rgba(255, 255, 255, 0.2);
  padding: 20px;
  border-radius: 10px;
  margin-bottom: 30px;
}

.add-music-form h3 {
  color: #6a5acd;
  margin: 0 0 15px 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 15px;
  margin-bottom: 15px;
}

.form-group {
  display: flex;
  flex-direction: column;
}

.form-group label {
  margin-bottom: 5px;
  color: #5c4b7b;
  font-weight: 500;
}

.form-group input {
  padding: 10px 15px;
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

.form-group input:focus {
  outline: none;
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 0 0 2px rgba(106, 90, 205, 0.2);
  background: rgba(255, 255, 255, 0.35);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.primary-btn {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  border: none;
  border-radius: 20px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.3);
}

.primary-btn:hover {
  background: linear-gradient(135deg, rgba(86, 70, 185, 0.9), rgba(118, 23, 206, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

.music-list-section {
  margin-top: 20px;
}

.music-list-section h3 {
  color: #6a5acd;
  margin: 0 0 15px 0;
  font-size: 1.2rem;
}

.search-filter {
  margin-bottom: 20px;
}

.search-input {
  width: 100%;
  max-width: 400px;
  padding: 10px 15px;
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

.search-input:focus {
  outline: none;
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 0 0 2px rgba(106, 90, 205, 0.2);
  background: rgba(255, 255, 255, 0.35);
}

.table-container {
  overflow-x: auto;
}

.music-table {
  width: 100%;
  border-collapse: collapse;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  overflow: hidden;
}

.music-table th,
.music-table td {
  padding: 12px 15px;
  text-align: left;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.music-table th {
  background: rgba(106, 90, 205, 0.3);
  color: #6a5acd;
  font-weight: 600;
}

.music-table tr:last-child td {
  border-bottom: none;
}

.music-table tr:hover {
  background: rgba(106, 90, 205, 0.1);
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
  background: rgba(46, 204, 113, 0.2);
  color: #2ecc71;
}

.edit-btn:hover {
  background: rgba(46, 204, 113, 0.3);
}

.delete-btn {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.delete-btn:hover {
  background: rgba(231, 76, 60, 0.3);
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

<style scoped>
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
  justify-content: flex-end;
}

.add-btn {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  border: none;
  border-radius: 20px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.3);
}

.add-btn:hover {
  background: linear-gradient(135deg, rgba(86, 70, 185, 0.9), rgba(118, 23, 206, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

.add-music-form {
  background: rgba(255, 255, 255, 0.2);
  padding: 20px;
  border-radius: 10px;
  margin-bottom: 30px;
}

.add-music-form h3 {
  color: #6a5acd;
  margin: 0 0 15px 0;
}

.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 15px;
  margin-bottom: 15px;
}

.form-group {
  display: flex;
  flex-direction: column;
}

.form-group label {
  margin-bottom: 5px;
  color: #5c4b7b;
  font-weight: 500;
}

.form-group input {
  padding: 10px 15px;
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

.form-group input:focus {
  outline: none;
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 0 0 2px rgba(106, 90, 205, 0.2);
  background: rgba(255, 255, 255, 0.35);
}

.form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

.primary-btn {
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(138, 43, 226, 0.8));
  color: white;
  border: none;
  border-radius: 20px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(106, 90, 205, 0.3);
}

.primary-btn:hover {
  background: linear-gradient(135deg, rgba(86, 70, 185, 0.9), rgba(118, 23, 206, 0.9));
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(106, 90, 205, 0.5);
}

.music-list-section {
  margin-top: 20px;
}

.music-list-section h3 {
  color: #6a5acd;
  margin: 0 0 15px 0;
  font-size: 1.2rem;
}

.search-filter {
  margin-bottom: 20px;
}

.search-input {
  width: 100%;
  max-width: 400px;
  padding: 10px 15px;
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

.search-input:focus {
  outline: none;
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 0 0 2px rgba(106, 90, 205, 0.2);
  background: rgba(255, 255, 255, 0.35);
}

.table-container {
  overflow-x: auto;
}

.music-table {
  width: 100%;
  border-collapse: collapse;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  overflow: hidden;
}

.music-table th,
.music-table td {
  padding: 12px 15px;
  text-align: left;
  border-bottom: 1px solid rgba(255, 255, 255, 0.1);
}

.music-table th {
  background: rgba(106, 90, 205, 0.3);
  color: #6a5acd;
  font-weight: 600;
}

.music-table tr:last-child td {
  border-bottom: none;
}

.music-table tr:hover {
  background: rgba(106, 90, 205, 0.1);
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
  background: rgba(46, 204, 113, 0.2);
  color: #2ecc71;
}

.edit-btn:hover {
  background: rgba(46, 204, 113, 0.3);
}

.delete-btn {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.delete-btn:hover {
  background: rgba(231, 76, 60, 0.3);
}
</style>