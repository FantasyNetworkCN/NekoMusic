<template>
  <div class="home-view">
    <div class="content">
      <div class="welcome-card">
        <h2 class="welcome-title">欢迎使用 NekoMusic</h2>
        <p class="welcome-subtitle">使用上方的搜索框来搜索您喜欢的音乐</p>
        <div class="decoration-element">
          <div class="music-note note-1">♪</div>
          <div class="music-note note-2">♫</div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted } from 'vue'

onMounted(() => {
  // 添加任何主页特定的逻辑
})
</script>

<style scoped>
.content {
  max-width: 800px;
  margin: 0 auto;
  text-align: center;
  position: relative;
  padding-top: 40px;
}

.welcome-card {
  background: rgba(255, 255, 255, 0.3);
  border-radius: 25px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  position: relative;
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  margin: 20px auto;
  max-width: 90%;
  border: 1px solid rgba(255, 255, 255, 0.2);
  overflow: hidden;
  transition: transform 0.3s ease, box-shadow 0.3s ease;
}

.welcome-card::before {
  content: '';
  position: absolute;
  top: -10px;
  left: -10px;
  right: -10px;
  bottom: -10px;
  background: linear-gradient(45deg, #ff9ec0, #6a5acd, #84ffff, #ff9ec0);
  background-size: 400%;
  border-radius: 30px;
  z-index: -1;
  filter: blur(20px);
  opacity: 0.6;
  animation: gradientShift 10s ease infinite;
}

.welcome-title {
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

.welcome-subtitle {
  color: #887bb0;
  font-size: 1.2rem;
  margin-bottom: 30px;
  position: relative;
  z-index: 1;
}

.decoration-element {
  position: relative;
  height: 60px;
  margin-top: 20px;
}

.music-note {
  position: absolute;
  font-size: 1.8rem;
  opacity: 0.7;
  animation: float 3s ease-in-out infinite;
  position: relative;
  z-index: 1;
}

.note-1 {
  top: 0;
  left: 30%;
  color: #ff9ec0;
  animation-delay: 0s;
}

.note-2 {
  top: 10px;
  right: 30%;
  color: #6a5acd;
  animation-delay: 1.5s;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0px) rotate(0deg);
  }
  50% {
    transform: translateY(-10px) rotate(5deg);
  }
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

.welcome-card:hover {
  transform: translateY(-5px);
  box-shadow: 0 12px 40px rgba(31, 38, 135, 0.5);
}
</style>