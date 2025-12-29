<template>
  <div class="admin-subpage">
    <h2>音乐管理</h2>
    <p>这里是音乐管理页面的内容</p>
    <div class="content-placeholder">
      <p>音乐列表、添加音乐、编辑音乐等功能将在这里实现</p>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'

const router = useRouter()

onMounted(() => {
  const storedAdminInfo = localStorage.getItem('adminToken')
  if (!storedAdminInfo) {
    // 如果没有存储的管理员信息，重定向到登录页面
    router.push('/admin/login')
  }
})
</script>

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

.content-placeholder {
  margin-top: 20px;
  padding: 20px;
  background: rgba(255, 255, 255, 0.2);
  border-radius: 10px;
  text-align: center;
  color: #887bb0;
}
</style>