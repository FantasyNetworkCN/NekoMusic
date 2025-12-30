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
                <label>音乐名称 *</label>
                <input type="text" v-model="newMusic.title" placeholder="请输入音乐名称" />
              </div>
              <div class="form-group">
                <label>艺术家 *</label>
                <input type="text" v-model="newMusic.artist" placeholder="请输入艺术家" />
              </div>
              <div class="form-group">
                <label>专辑</label>
                <input type="text" v-model="newMusic.album" placeholder="请输入专辑" />
              </div>
              <div class="form-group">
                <label>时长(秒)</label>
                <input type="number" v-model="newMusic.duration" placeholder="请输入音乐时长(秒)" />
              </div>
            </div>
            <div class="form-actions">
              <button class="primary-btn" @click="addMusic">添加音乐</button>
            </div>
          </div>
          
          <!-- 编辑音乐悬浮窗 -->
          <Transition name="modal">
            <div v-if="editingMusic" class="edit-modal-overlay" @click="closeEditModal">
              <div class="edit-modal" @click.stop ref="editModalRef">
                <div class="modal-header">
                  <h3>编辑音乐</h3>
                  <button class="close-btn" @click="cancelEdit">&times;</button>
                </div>
                <div class="modal-content">
                  <div class="form-group">
                    <label>音乐名称 *</label>
                    <input type="text" v-model="editingMusic.title" placeholder="请输入音乐名称" />
                  </div>
                  <div class="form-group">
                    <label>艺术家 *</label>
                    <input type="text" v-model="editingMusic.artist" placeholder="请输入艺术家" />
                  </div>
                  <div class="form-group">
                    <label>专辑</label>
                    <input type="text" v-model="editingMusic.album" placeholder="请输入专辑" />
                  </div>
                  <div class="form-group">
                    <label>时长(秒)</label>
                    <input type="number" v-model="editingMusic.duration" placeholder="请输入音乐时长(秒)" />
                  </div>
                </div>
                <div class="form-actions modal-actions">
                  <button class="secondary-btn" @click="cancelEdit">取消</button>
                  <button class="primary-btn" @click="saveEdit">保存更改</button>
                </div>
              </div>
            </div>
          </Transition>
          
          <div class="music-list-section">
            <h3>音乐列表</h3>
            <div class="search-filter">
              <input 
                type="text" 
                v-model="searchQuery" 
                @input="updateSearchResults"
                placeholder="搜索音乐或艺术家..." 
                class="search-input"
              />
            </div>
            
            <div v-if="isLoading" class="loading">
              <p>正在加载音乐列表...</p>
            </div>
            
            <div v-else class="table-container">
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
                    <td>{{ formatDate(music.createdAt) }}</td>
                    <td>
                      <button class="action-btn edit-btn" @click="editMusic(music)">编辑</button>
                      <button class="action-btn delete-btn" @click="deleteMusic(music.id)">删除</button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
            
            <div v-if="!isLoading && filteredMusicList.length === 0" class="no-data">
              <p>暂无音乐数据</p>
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
import API_CONFIG from '@/config/apiConfig.js'

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
  
  // 获取音乐列表
  fetchMusicList()
})

// 音乐数据
const musicList = ref([])
const filteredMusicList = ref([])

// 表单状态
const showAddForm = ref(false)
const newMusic = ref({
  title: '',
  artist: '',
  album: '',
  duration: 0,
  filePath: '',
  uploadUserId: 0
})
const editingMusic = ref(null)
const searchQuery = ref('')
const isLoading = ref(false)

// 悬浮窗拖动状态
const editModalRef = ref(null)
const modalPosition = ref({ x: 0, y: 0 })
const isDragging = ref(false)
const dragOffset = ref({ x: 0, y: 0 })

// 获取音乐列表
const fetchMusicList = async () => {
  isLoading.value = true
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/list`, {
      method: 'GET',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      }
    })
    
    const data = await response.json()
    if (data.success) {
      // 将API返回的音乐数据转换为前端可用的格式
      musicList.value = data.data.map(music => ({
        ...music,
        uploadTime: new Date(music.createdAt) // 转换日期格式
      }))
      filteredMusicList.value = [...musicList.value]
    } else {
      console.error('获取音乐列表失败:', data.message)
      alert(data.message || '获取音乐列表失败')
    }
  } catch (error) {
    console.error('获取音乐列表时出错:', error)
    alert('获取音乐列表时出错')
  } finally {
    isLoading.value = false
  }
}

// 添加音乐
const addMusic = async () => {
  if (!newMusic.value.title || !newMusic.value.artist) {
    alert('请填写音乐名称和艺术家')
    return
  }
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: JSON.stringify({
        title: newMusic.value.title,
        artist: newMusic.value.artist,
        album: newMusic.value.album || '未知专辑',
        duration: newMusic.value.duration || 0,
        filePath: newMusic.value.filePath || '',
        uploadUserId: newMusic.value.uploadUserId || 0
      })
    })
    
    const data = await response.json()
    if (data.success) {
      // 添加新音乐到列表顶部
      musicList.value.unshift({
        ...data.data,
        uploadTime: new Date(data.data.createdAt)
      })
      filteredMusicList.value = [...musicList.value]
      resetForm()
      alert('添加音乐成功')
    } else {
      console.error('添加音乐失败:', data.message)
      alert(data.message || '添加音乐失败')
    }
  } catch (error) {
    console.error('添加音乐时出错:', error)
    alert('添加音乐时出错')
  }
}

// 重置表单
const resetForm = () => {
  newMusic.value = {
    title: '',
    artist: '',
    album: '',
    duration: 0,
    filePath: '',
    uploadUserId: 0
  }
  showAddForm.value = false
}

// 编辑音乐
const editMusic = async (music) => {
  editingMusic.value = { ...music } // 复制音乐对象以避免直接修改原数据
}

// 关闭编辑悬浮窗
const closeEditModal = () => {
  editingMusic.value = null
}

// 开始拖动悬浮窗
const startDrag = (e) => {
  if (e.target.classList.contains('close-btn')) return // 防止在关闭按钮上拖动
  
  isDragging.value = true
  const rect = editModalRef.value.getBoundingClientRect()
  // 计算鼠标相对于悬浮窗的偏移量
  dragOffset.value = {
    x: e.clientX - rect.x,  // 使用rect.x而不是rect.left
    y: e.clientY - rect.y   // 使用rect.y而不是rect.top
  }
  
  // 添加事件监听器
  const handleMouseMove = (e) => {
    if (!isDragging.value) return
    
    // 根据鼠标位置和偏移量计算新位置
    let x = e.clientX - dragOffset.value.x
    let y = e.clientY - dragOffset.value.y
    
    // 确保悬浮窗在屏幕范围内
    const maxX = window.innerWidth - editModalRef.value.offsetWidth
    const maxY = window.innerHeight - editModalRef.value.offsetHeight
    
    // 确保不小于0，不大于最大值
    x = Math.max(0, Math.min(x, maxX))
    y = Math.max(0, Math.min(y, maxY))
    
    modalPosition.value = { x, y }
  }
  
  const handleMouseUp = () => {
    isDragging.value = false
    // 移除事件监听器
    document.removeEventListener('mousemove', handleMouseMove)
    document.removeEventListener('mouseup', handleMouseUp)
  }
  
  // 添加事件监听器
  document.addEventListener('mousemove', handleMouseMove)
  document.addEventListener('mouseup', handleMouseUp)
  
  // 阻止默认行为（如选择文本）
  e.preventDefault()
}

// 停止拖动
const stopDrag = () => {
  isDragging.value = false
}

// 保存编辑
const saveEdit = async () => {
  if (!editingMusic.value.title || !editingMusic.value.artist) {
    alert('请填写音乐名称和艺术家')
    return
  }
  
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: JSON.stringify({
        id: editingMusic.value.id,
        title: editingMusic.value.title,
        artist: editingMusic.value.artist,
        album: editingMusic.value.album,
        duration: editingMusic.value.duration,
        filePath: editingMusic.value.filePath,
        uploadUserId: editingMusic.value.uploadUserId
      })
    })
    
    const data = await response.json()
    if (data.success) {
      // 更新本地列表
      const index = musicList.value.findIndex(m => m.id === editingMusic.value.id)
      if (index !== -1) {
        musicList.value[index] = {
          ...data.data,
          uploadTime: new Date(data.data.createdAt)
        }
        filteredMusicList.value = [...musicList.value]
      }
      editingMusic.value = null
      alert('编辑音乐成功')
    } else {
      console.error('编辑音乐失败:', data.message)
      alert(data.message || '编辑音乐失败')
    }
  } catch (error) {
    console.error('编辑音乐时出错:', error)
    alert('编辑音乐时出错')
  }
}

// 取消编辑
const cancelEdit = () => {
  editingMusic.value = null
}

// 删除音乐
const deleteMusic = async (id) => {
  if (confirm('确定要删除这首音乐吗？此操作不可撤销。')) {
    try {
      const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/${id}`, {
        method: 'DELETE',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
        }
      })
      
      const data = await response.json()
      if (data.success) {
        musicList.value = musicList.value.filter(music => music.id !== id)
        filteredMusicList.value = [...musicList.value]
        alert('删除音乐成功')
      } else {
        console.error('删除音乐失败:', data.message)
        alert(data.message || '删除音乐失败')
      }
    } catch (error) {
      console.error('删除音乐时出错:', error)
      alert('删除音乐时出错')
    }
  }
}

// 格式化日期
const formatDate = (date) => {
  if (!date) return ''
  return new Date(date).toLocaleDateString('zh-CN')
}

// 使用API搜索音乐
const searchMusicAPI = async (query) => {
  try {
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${localStorage.getItem('adminToken')}`
      },
      body: JSON.stringify({ query: query })
    })
    
    const data = await response.json()
    if (data.success && data.results) {
      // 将API返回的音乐数据转换为前端可用的格式
      return data.results.map(music => ({
        ...music,
        uploadTime: new Date(music.createdAt) // 转换日期格式
      }))
    } else {
      console.error('搜索音乐失败:', data.message)
      return []
    }
  } catch (error) {
    console.error('搜索音乐时出错:', error)
    return []
  }
}

// 监听搜索查询变化
const updateSearchResults = async () => {
  if (!searchQuery.value.trim()) {
    filteredMusicList.value = musicList.value
  } else {
    // 可以选择使用API搜索或本地搜索
    // 这里我们使用本地搜索，因为已经获取了所有音乐数据
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

.secondary-btn {
  background: rgba(149, 165, 166, 0.2);
  color: #7f8c8d;
  border: none;
  border-radius: 20px;
  padding: 10px 20px;
  cursor: pointer;
  font-size: 0.9rem;
  transition: all 0.3s ease;
  box-shadow: 0 4px 10px rgba(149, 165, 166, 0.3);
}

.secondary-btn:hover {
  background: rgba(127, 140, 141, 0.3);
  transform: translateY(-2px);
  box-shadow: 0 6px 15px rgba(149, 165, 166, 0.5);
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

.loading {
  text-align: center;
  padding: 20px;
  color: #6a5acd;
  font-size: 1.1rem;
}

.no-data {
  text-align: center;
  padding: 40px;
  color: #7f8c8d;
  font-size: 1.1rem;
}

/* 编辑悬浮窗样式 */
.edit-modal-overlay {
  position: fixed;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  justify-content: center;
  align-items: flex-start;  /* 改为flex-start，并配合padding-top定位 */
  padding-top: 15vh;  /* 增加顶部填充，让悬浮窗位于垂直方向的60%左右 */
  z-index: 9999;
}

.edit-modal {
  background: rgba(255, 255, 255, 0.9);
  border-radius: 15px;
  box-shadow: 0 10px 30px rgba(0, 0, 0, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  min-width: 400px;
  max-width: 500px;
  display: flex;
  flex-direction: column;
  position: relative; /* 使用相对定位，让其在overlay中居中 */
  cursor: default;
  z-index: 10000;
}

/* Vue过渡动画 */
.modal-enter-active, .modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from, .modal-leave-to {
  opacity: 0;
}

.modal-enter-active .edit-modal, .modal-leave-active .edit-modal {
  transition: transform 0.3s ease;
}

.modal-enter-from .edit-modal {
  transform: scale(0.8);
}

.modal-leave-to .edit-modal {
  transform: scale(0.8);
}

.modal-header {
  padding: 15px 20px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: rgba(106, 90, 205, 0.2);
  border-radius: 15px 15px 0 0;
  cursor: default; /* 移除拖动光标 */
}

.modal-header h3 {
  margin: 0;
  color: #6a5acd;
  font-size: 1.2rem;
}

.close-btn {
  background: transparent;
  border: none;
  color: #6a5acd;
  font-size: 1.5rem;
  cursor: pointer;
  width: 30px;
  height: 30px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  transition: background 0.3s ease;
}

.close-btn:hover {
  background: rgba(231, 76, 60, 0.2);
  color: #e74c3c;
}

.modal-content {
  padding: 20px;
  flex: 1;
}

.modal-content .form-group {
  margin-bottom: 15px;
}

.modal-actions {
  padding: 15px 20px;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 0 0 15px 15px;
}

/* Vue过渡动画 */
.modal-enter-active, .modal-leave-active {
  transition: opacity 0.3s ease;
}

.modal-enter-from, .modal-leave-to {
  opacity: 0;
}

.modal-enter-active .edit-modal, .modal-leave-active .edit-modal {
  transition: transform 0.3s ease;
}

.modal-enter-from .edit-modal {
  transform: scale(0.8);
}

.modal-leave-to .edit-modal {
  transform: scale(0.8);
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
  
  .form-grid {
    grid-template-columns: 1fr;
  }
  
  .edit-modal {
    min-width: 300px;
    margin: 10px;
    max-width: calc(100% - 20px);
  }
}
</style>