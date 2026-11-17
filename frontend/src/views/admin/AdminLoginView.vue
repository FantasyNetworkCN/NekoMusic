<template>
  <AmbientBackdrop />

  <PageShell width="narrow" centered>
    <div class="auth">
      <span class="auth__glow" aria-hidden="true" />

      <div class="auth__card">
        <header class="auth__head">
          <div class="auth__logo">
            <NIcon name="shield-check" :size="26" />
          </div>
          <h1 class="auth__title">管理中心</h1>
          <p class="auth__subtitle">使用 NekoAuth 账号登录，仅管理员角色可进入</p>
        </header>

        <div class="auth__body">
          <div v-if="isLoading" class="auth__checking">
            <NSpinner :size="24" />
            <span>正在确认 NekoAuth 权限…</span>
          </div>
          <template v-else>
            <p class="auth__hint">后台不再提供独立管理员账号和密码。</p>
            <p class="auth__error" role="alert">{{ errorMessage || '\u00A0' }}</p>
            <NButton type="button" variant="primary" size="lg" block @click="loginWithAuth">
              {{ errorMessage ? '切换 NekoAuth 账号' : '使用 NekoAuth 登录' }}
            </NButton>
          </template>
        </div>

        <p class="auth__foot">
          <RouterLink to="/" class="auth__link">
            <NIcon name="arrow-left" :size="14" />
            返回站点
          </RouterLink>
        </p>
      </div>
    </div>
  </PageShell>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NSpinner } from '@/ui'
import { PageShell, AmbientBackdrop } from '@/layouts'
import { openAuthDialog } from '@/composables/useAuthDialog'
import { clearAdminIdentity, refreshAdminIdentity } from '@/api/adminAuth.js'
import { logoutUserGlobally } from '@/api/session.js'

const router = useRouter()
const route = useRoute()
const isLoading = ref(true)
const errorMessage = ref('')

function returnTo() {
  const raw = Array.isArray(route.query.returnTo) ? route.query.returnTo[0] : route.query.returnTo
  return typeof raw === 'string' && raw.startsWith('/admin') && !raw.startsWith('//')
    ? raw
    : '/admin'
}

async function checkAccess() {
  isLoading.value = true
  try {
    const status = await refreshAdminIdentity()
    if (status === 'admin') {
      await router.replace(returnTo())
    } else if (status === 'forbidden') {
      errorMessage.value = '当前 NekoAuth 账号不是管理员'
    } else if (status === 'unavailable') {
      errorMessage.value = '暂时无法确认后台权限，请稍后重试'
    } else if (route.query.denied) {
      errorMessage.value = '当前 NekoAuth 账号没有后台权限'
    }
  } finally {
    isLoading.value = false
  }
}

async function loginWithAuth() {
  await logoutUserGlobally()
  localStorage.removeItem('userToken')
  clearAdminIdentity()
  openAuthDialog('login', returnTo())
}

onMounted(checkAccess)
</script>

<style scoped>
/* ===== 与前台登录卡片一致的容器与特效 ===== */
.auth {
  position: relative;
  width: 100%;
  max-width: 400px;
}

.auth__glow {
  position: absolute;
  inset: -14% -10%;
  border-radius: 50%;
  background: radial-gradient(circle at 50% 40%, rgba(95, 208, 224, 0.22), transparent 62%);
  filter: blur(48px);
  pointer-events: none;
  z-index: -1;
  animation: authGlow 8s var(--n-ease-in-out) infinite;
}

@keyframes authGlow {
  0%,
  100% { opacity: 0.75; }
  50% { opacity: 1; }
}

.auth__card {
  position: relative;
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-xl);
  background: var(--n-surface-strong);
  backdrop-filter: var(--n-blur);
  -webkit-backdrop-filter: var(--n-blur);
  box-shadow: var(--n-shadow-lg);
  overflow: hidden;
  animation: authCardIn 0.5s var(--n-ease) both;
}

@keyframes authCardIn {
  from {
    opacity: 0;
    transform: translateY(14px) scale(0.985);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.auth__card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto;
  height: 1px;
  background: linear-gradient(90deg, transparent, var(--n-accent-line), transparent);
}

.auth__head {
  padding: var(--n-space-8) var(--n-space-8) 0;
  text-align: center;
}

.auth__logo {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin: 0 auto var(--n-space-4);
  border-radius: var(--n-radius);
  background: linear-gradient(135deg, var(--n-accent-strong), var(--n-accent));
  color: var(--n-text-inverse);
  box-shadow: 0 8px 26px rgba(95, 208, 224, 0.3);
  animation: authLogoIn 0.5s var(--n-ease) 0.08s both;
}

@keyframes authLogoIn {
  from {
    opacity: 0;
    transform: scale(0.82);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.auth__title {
  margin: 0 0 var(--n-space-1);
  font-size: var(--n-text-lg);
  font-weight: var(--n-weight-bold);
  letter-spacing: -0.02em;
  background: var(--n-gradient-text);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.auth__subtitle {
  margin: 0;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.auth__body {
  padding: var(--n-space-6) var(--n-space-8) 0;
}

.auth__checking {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--n-space-3);
  min-height: 92px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
}

.auth__hint {
  margin: 0 0 var(--n-space-3);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  text-align: center;
}

.auth__field {
  margin-bottom: var(--n-space-4);
}

.auth__label {
  display: block;
  margin-bottom: var(--n-space-2);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  font-weight: var(--n-weight-medium);
}

.auth__error {
  min-height: 18px;
  margin: 0 0 var(--n-space-3);
  color: var(--n-danger);
  font-size: var(--n-text-xs);
  text-align: center;
}

.auth__foot {
  margin: 0;
  padding: var(--n-space-5) var(--n-space-8) var(--n-space-8);
  text-align: center;
}

.auth__link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  font-weight: var(--n-weight-medium);
}

@media (hover: hover) {
  .auth__link:hover {
    color: var(--n-accent-strong);
  }
}

@media (prefers-reduced-motion: reduce) {
  .auth__card,
  .auth__logo,
  .auth__glow {
    animation: none;
  }
}
</style>
