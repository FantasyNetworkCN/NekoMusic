<template>
  <div class="register-container">
    <div class="register-card">
      <h2 class="register-title">用户注册</h2>
      <form @submit.prevent="handleRegister" class="register-form">
        <div class="form-group">
          <input
            type="text"
            v-model="username"
            class="form-input"
            placeholder="用户名"
            required
          />
        </div>
        
        <div class="form-group">
          <input
            type="email"
            v-model="email"
            class="form-input"
            placeholder="邮箱"
            required
          />
        </div>

        <div class="form-group">
          <div style="display: flex; gap: 10px;">
            <input
                type="text"
                v-model="verificationCode"
                class="form-input"
                placeholder="验证码"
                required
                style="flex: 1;"
            />
            <button
                type="button"
                class="verification-btn"
                @click="sendVerificationCode"
                :disabled="codeSending || countdown > 0"
            >
              {{ codeBtnText }}
            </button>
          </div>
        </div>
        
        <div class="form-group">
          <input
            type="password"
            v-model="password"
            class="form-input"
            placeholder="密码"
            required
          />
        </div>
        
        <div class="form-group">
          <input
            type="password"
            v-model="confirmPassword"
            class="form-input"
            placeholder="确认密码"
            required
          />
        </div>
        
        <button type="submit" class="register-btn" :disabled="loading">
          <span v-if="loading">注册中...</span>
          <span v-else>注册</span>
        </button>
      </form>
      
      <div class="register-footer">
        <p>已有账户？<a href="#" @click.prevent="goToLogin">立即登录</a></p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { useRouter } from 'vue-router'
import axios from 'axios'

const router = useRouter()
const username = ref('')
const email = ref('')
const password = ref('')
const confirmPassword = ref('')
const verificationCode = ref('')
const loading = ref(false)
const codeSending = ref(false)
const countdown = ref(0)
const countdownInterval = ref(null)

// 验证码按钮文字
const codeBtnText = computed(() => {
  return countdown.value > 0 ? `${countdown.value}秒后重发` : '获取验证码'
})

// 发送验证码
const sendVerificationCode = async () => {
  if (!email.value) {
    alert('请先输入邮箱地址')
    return
  }
  
  // 验证邮箱格式
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
  if (!emailRegex.test(email.value)) {
    alert('请输入有效的邮箱地址')
    return
  }
  
  codeSending.value = true
  try {
    const response = await axios.post('http://localhost:8080/api/user/send-verification', {
      email: email.value
    })
    
    if (response.data.success) {
      alert('验证码已发送至您的邮箱')
      startCountdown()
    } else {
      alert(response.data.message || '发送验证码失败')
    }
  } catch (error) {
    console.error('发送验证码失败:', error)
    if (error.response) {
      alert(error.response.data.message || '发送验证码失败')
    } else {
      alert('网络错误，请检查服务器连接')
    }
  } finally {
    codeSending.value = false
  }
}

// 开始倒计时
const startCountdown = () => {
  countdown.value = 60
  countdownInterval.value = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearInterval(countdownInterval.value)
    }
  }, 1000)
}

// 处理注册逻辑
const handleRegister = async () => {
  if (password.value !== confirmPassword.value) {
    alert('两次输入的密码不一致')
    return
  }
  
  loading.value = true
  try {
    const response = await axios.post('http://localhost:8080/api/user/register', {
      username: username.value,
      email: email.value,
      password: password.value,
      verificationCode: verificationCode.value
    })
    
    if (response.data.success) {
      alert('注册成功！请登录您的账户。')
      router.push('/login')
    } else {
      alert(response.data.message || '注册失败')
    }
  } catch (error) {
    console.error('注册失败:', error)
    if (error.response) {
      alert(error.response.data.message || '注册失败')
    } else {
      alert('网络错误，请检查服务器连接')
    }
  } finally {
    loading.value = false
  }
}

// 跳转到登录页面
const goToLogin = () => {
  router.push('/login')
}
</script>

<style scoped>
.register-container {
  min-height: 70vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.register-card {
  background: rgba(255, 255, 255, 0.3);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 20px;
  padding: 40px;
  width: 100%;
  max-width: 400px;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  border: 1px solid rgba(255, 255, 255, 0.18);
  position: relative;
  overflow: hidden;
}

.register-card::before {
  content: '';
  position: absolute;
  top: -10px;
  left: -10px;
  right: -10px;
  bottom: -10px;
  background: linear-gradient(45deg, #ff9ec0, #6a5acd, #84ffff, #ff9ec0);
  background-size: 400%;
  border-radius: 25px;
  z-index: -1;
  filter: blur(20px);
  opacity: 0.6;
  animation: gradientShift 10s ease infinite;
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

.register-title {
  text-align: center;
  margin-bottom: 30px;
  font-size: 1.8rem;
  color: #6a5acd;
  text-shadow: 2px 2px 4px rgba(0, 0, 0, 0.1);
  background: linear-gradient(45deg, #ff9ec0, #6a5acd, #84ffff);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  background-clip: text;
  position: relative;
  z-index: 1;
}

.register-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.form-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.form-input {
  padding: 14px 20px;
  border: none;
  border-radius: 30px;
  font-size: 1rem;
  outline: none;
  box-shadow: 0 8px 32px rgba(31, 38, 135, 0.37);
  background: rgba(255, 255, 255, 0.25);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border: 1px solid rgba(255, 255, 255, 0.18);
  transition: all 0.3s ease;
  color: #333;
  width: 100%;
}

.form-input::placeholder {
  color: rgba(92, 75, 123, 0.6);
}

.form-input:focus {
  border: 1px solid rgba(106, 90, 205, 0.5);
  box-shadow: 0 8px 32px rgba(106, 90, 205, 0.3);
  background: rgba(255, 255, 255, 0.35);
}

.verification-btn {
  padding: 14px 15px;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(147, 112, 219, 0.8));
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 0.9rem;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 8px 32px rgba(106, 90, 205, 0.3);
  backdrop-filter: blur(5px);
  -webkit-backdrop-filter: blur(5px);
  width: auto;
  white-space: nowrap;
}

.verification-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 30px rgba(106, 90, 205, 0.5);
  background: linear-gradient(135deg, rgba(92, 75, 123, 0.9), rgba(122, 91, 192, 0.9));
}

.verification-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.register-btn {
  padding: 14px 20px;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.8), rgba(147, 112, 219, 0.8));
  color: white;
  border: none;
  border-radius: 30px;
  font-size: 1.1rem;
  font-weight: bold;
  cursor: pointer;
  transition: all 0.3s ease;
  box-shadow: 0 8px 32px rgba(106, 90, 205, 0.3);
  backdrop-filter: blur(5px);
  -webkit-backdrop-filter: blur(5px);
  width: 100%;
  margin-top: 10px;
}

.register-btn:hover:not(:disabled) {
  transform: translateY(-2px);
  box-shadow: 0 10px 30px rgba(106, 90, 205, 0.5);
  background: linear-gradient(135deg, rgba(92, 75, 123, 0.9), rgba(122, 91, 192, 0.9));
}

.register-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.register-footer {
  text-align: center;
  margin-top: 20px;
  color: #6a5acd;
}

.register-footer a {
  color: #6a5acd;
  text-decoration: none;
  font-weight: 500;
}

.register-footer a:hover {
  text-decoration: underline;
}

@media (max-width: 768px) {
  .register-card {
    padding: 30px 20px;
    margin: 0 10px;
  }
}
</style>