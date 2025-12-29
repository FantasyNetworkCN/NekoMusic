<template>
  <footer class="footer">
    <div class="footer-content">
      <div class="footer-info">
        <div class="footer-text">
          <p>备案号：蜀ICP备2025177767号-1</p>
          <p>组织：<span class="fantasy-network">Fantasy Network「梦幻网络」</span></p>
        </div>
      </div>
      <div class="footer-links">
        <a href="about" class="footer-link">关于我们</a>
      </div>
    </div>
    <div class="footer-bottom">
      <p>&copy; {{ new Date().getFullYear() }} NekoMusic. All rights reserved.</p>
    </div>
  </footer>
</template>

<script setup>
// Footer组件逻辑
</script>

<style scoped>
.footer {
  background: linear-gradient(135deg, #6a5acd, #9370db);
  color: white;
  padding: 30px 0 10px;
  margin-top: auto;
  width: 100%;
}

.footer-content {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 20px;
}

.footer-info {
  flex: 1;
  min-width: 250px;
}

.footer-text p {
  margin: 5px 0;
  font-size: 0.9rem;
}

.fantasy-network {
  font-weight: bold;
  color: #ffccf9;
  text-shadow: 0 0 5px rgba(255, 204, 249, 0.5);
}

.footer-links {
  display: flex;
  gap: 20px;
  flex: 0 0 auto;
}

.footer-link {
  color: #ffccf9;
  text-decoration: none;
  font-size: 0.9rem;
  transition: color 0.3s ease;
}

.footer-link:hover {
  color: white;
  text-decoration: underline;
}

.footer-bottom {
  max-width: 1200px;
  margin: 20px auto 0;
  padding: 10px 20px;
  text-align: center;
  border-top: 1px solid rgba(255, 255, 255, 0.2);
  font-size: 0.8rem;
  color: #e0d6f0;
}

@media (max-width: 768px) {
  .footer-content {
    flex-direction: column;
    text-align: center;
  }
  
  .footer-links {
    justify-content: center;
    margin-top: 10px;
  }
}
</style>