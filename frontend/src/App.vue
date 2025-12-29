<script setup>
import SearchHeader from './components/SearchHeader.vue'
</script>

<template>
  <div id="app">
    <SearchHeader />
    <main>
      <div class="content">
        <h2>欢迎使用 NekoMusic</h2>
        <p>使用上方的搜索框来搜索您喜欢的音乐</p>
      </div>
    </main>
  </div>
</template>

<style scoped>
main {
  flex: 1;
  padding: 20px;
}

.content {
  max-width: 800px;
  margin: 0 auto;
  text-align: center;
}

.content h2 {
  color: #2c3e50;
  margin-bottom: 1rem;
}

.content p {
  color: #7f8c8d;
  font-size: 1.1rem;
}
</style>
