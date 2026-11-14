<script setup>
/**
 * 一起听分享链接落地页。
 *
 * 网页不提供房间界面，也不请求房间状态；只把随机 token 交给原生客户端。
 */
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import NIcon from '@/icons/NIcon.vue'
import { NButton, NCard, NSpinner } from '@/ui'
import { AmbientBackdrop, PageShell } from '@/layouts'
import { tryOpenTogetherInApp } from '@/utils/nativeAppOpen'

const route = useRoute()
const attempted = ref(false)
const token = computed(() => String(route.params.token || '').trim())
const valid = computed(() => /^[A-Za-z0-9_-]{8,128}$/.test(token.value))

function openApp(force = true) {
  if (!valid.value) return
  attempted.value = true
  tryOpenTogetherInApp(token.value, { force })
}

onMounted(() => {
  if (!valid.value) return
  window.setTimeout(() => openApp(false), 120)
})
</script>

<template>
  <AmbientBackdrop />

  <PageShell width="narrow" centered>
    <NCard pad="lg" class="together-launch">
      <template v-if="valid">
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
</style>
