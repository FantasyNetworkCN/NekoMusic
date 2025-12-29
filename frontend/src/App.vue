<script setup>
import SearchHeader from './components/SearchHeader.vue'
import Footer from './components/Footer.vue'
</script>

<template>
  <div id="app">
    <SearchHeader />
    <main>
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
    </main>
    <CatGirl />
    <Footer />
  </div>
</template>

<style scoped>
#app {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

main {
  flex: 1;
  padding: 20px;
  background: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='100' height='100' viewBox='0 0 100 100'%3E%3Ccircle cx='50' cy='50' r='2' fill='%23ffccf9' opacity='0.3'/%3E%3C/svg%3E");
  background-size: 100px 100px;
}

.content {
  max-width: 800px;
  margin: 0 auto;
  text-align: center;
  position: relative;
  padding-top: 40px;
}

.welcome-card {
  background: rgba(255, 255, 255, 0.85);
  border-radius: 20px;
  padding: 40px;
  box-shadow: 0 8px 32px rgba(255, 182, 193, 0.3);
  border: 1px solid rgba(255, 182, 193, 0.3);
  position: relative;
  backdrop-filter: blur(10px);
  margin: 20px auto;
  max-width: 90%;
}

.welcome-title {
  color: #6a5acd;
  margin-bottom: 1rem;
  font-size: 2.2rem;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
  background: linear-gradient(45deg, #ff9ec0, #6a5acd);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
}

.welcome-subtitle {
  color: #887bb0;
  font-size: 1.2rem;
  margin-bottom: 30px;
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
</style>
