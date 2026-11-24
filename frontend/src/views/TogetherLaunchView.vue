<script setup>
/**
 * 一起听分享链接落地页。
 *
 * 网页不提供房间界面，也不请求房间状态；只把随机 token 交给原生客户端。
 * App 内置浏览器无法可靠唤起自定义协议，此时停止拉起并引导用户改用系统浏览器。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { AmbientBackdrop, PageShell } from '@/layouts'
import { detectInAppBrowser } from '@/utils/inAppBrowser'
import { tryOpenTogetherInApp } from '@/utils/nativeAppOpen'

const route = useRoute()
const attempted = ref(false)
const linkCopied = ref(false)
const copyFailed = ref(false)
const token = computed(() => String(route.params.token || '').trim())
const valid = computed(() => /^[A-Za-z0-9_-]{8,128}$/.test(token.value))
const inAppBrowser = detectInAppBrowser()
const shareUrl = (() => {
  try {
    const url = new URL(window.location.href)
    url.searchParams.delete('nekoweb')
    return url.href
  } catch {
    return window.location.href
  }
})()
let copyTimer = null

function openApp(force = true) {
  if (!valid.value) return
  attempted.value = true
  tryOpenTogetherInApp(token.value, { force })
}

function copyWithLegacyApi(text) {
  const input = document.createElement('textarea')
  try {
    input.value = text
    input.setAttribute('readonly', '')
    input.style.position = 'fixed'
    input.style.opacity = '0'
    document.body.appendChild(input)
    input.select()
    input.setSelectionRange(0, text.length)
    if (!document.execCommand('copy')) throw new Error('execCommand copy failed')
  } finally {
    input.remove()
  }
}

async function copyShareLink() {
  copyFailed.value = false
  try {
    if (navigator.clipboard?.writeText) {
      try {
        await navigator.clipboard.writeText(shareUrl)
      } catch {
        copyWithLegacyApi(shareUrl)
      }
    } else {
      copyWithLegacyApi(shareUrl)
    }
    linkCopied.value = true
    if (copyTimer) window.clearTimeout(copyTimer)
    copyTimer = window.setTimeout(() => {
      linkCopied.value = false
    }, 2000)
  } catch {
    copyFailed.value = true
  }
}

onMounted(() => {
  if (!valid.value || inAppBrowser) return
  window.setTimeout(() => openApp(false), 120)
})

onBeforeUnmount(() => {
  if (copyTimer) window.clearTimeout(copyTimer)
})
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="narrow" centered>
    <NCard pad="lg" class="together-launch">
      <template v-if="valid && inAppBrowser">
        <span class="together-launch__icon together-launch__icon--warning">
          <NIcon name="warning" :size="30" />
        </span>
        <h1 class="together-launch__title">{{ $t('togetherLaunch.inAppTitle') }}</h1>
        <p class="together-launch__desc" role="alert">
          {{ $t('togetherLaunch.inAppDesc', { browser: inAppBrowser.name }) }}
        </p>
        <p class="together-launch__hint">{{ $t('togetherLaunch.inAppHint') }}</p>
        <NButton variant="primary" icon="copy" @click="copyShareLink">
          {{ linkCopied ? $t('togetherLaunch.copied') : $t('togetherLaunch.copyLink') }}
        </NButton>
        <p v-if="copyFailed" class="together-launch__copy-error" role="status">
          {{ $t('togetherLaunch.copyFailed') }}
        </p>
        <p class="together-launch__url">{{ shareUrl }}</p>
      </template>

      <template v-else-if="valid">
        <NSpinner size="lg" />
        <h1 class="together-launch__title">{{ $t('togetherLaunch.title') }}</h1>
        <p class="together-launch__desc">
          {{ attempted ? $t('togetherLaunch.retryHint') : $t('togetherLaunch.opening') }}
        </p>
        <NButton variant="primary" icon="external-link" @click="openApp(true)">
          {{ $t('togetherLaunch.openApp') }}
        </NButton>
      </template>

      <template v-else>
        <span class="together-launch__icon">
          <NIcon name="alert" :size="30" />
        </span>
        <h1 class="together-launch__title">{{ $t('togetherLaunch.invalidTitle') }}</h1>
        <p class="together-launch__desc" role="alert">{{ $t('togetherLaunch.invalidDesc') }}</p>
      </template>
    </NCard>
  </PageShell>
</template>

<style scoped>
.together-launch {
  width: min(520px, 100%);
  padding: clamp(36px, 6vw, 56px) clamp(24px, 5vw, 42px);
  text-align: center;
}

.together-launch :deep(.n-spinner) {
  margin: 0 auto var(--n-space-5);
}

.together-launch__icon {
  display: inline-grid;
  place-items: center;
  width: 58px;
  height: 58px;
  margin-bottom: var(--n-space-4);
  border-radius: 50%;
  color: var(--n-danger);
  background: color-mix(in srgb, var(--n-danger) 12%, transparent);
}

.together-launch__icon--warning {
  color: var(--n-warning);
  background: var(--n-warning-soft);
}

.together-launch__title {
  margin: 0 0 var(--n-space-2);
  font-size: clamp(1.3rem, 3vw, 1.7rem);
  font-weight: var(--n-weight-bold);
}

.together-launch__desc {
  margin: 0 0 var(--n-space-8);
  color: var(--n-text-muted);
  line-height: var(--n-leading-normal);
}

.together-launch__hint {
  margin: calc(var(--n-space-2) * -1) 0 var(--n-space-6);
  color: var(--n-text-muted);
  font-size: var(--n-text-sm);
  line-height: var(--n-leading-normal);
}

.together-launch__copy-error {
  margin: var(--n-space-3) 0 0;
  color: var(--n-danger);
  font-size: var(--n-text-sm);
}

.together-launch__url {
  margin: var(--n-space-5) 0 0;
  padding: var(--n-space-3);
  border: 1px solid var(--n-line);
  border-radius: var(--n-radius-control);
  background: var(--n-surface-soft);
  color: var(--n-text-muted);
  font-size: var(--n-text-xs);
  line-height: 1.5;
  overflow-wrap: anywhere;
  user-select: all;
}
</style>
