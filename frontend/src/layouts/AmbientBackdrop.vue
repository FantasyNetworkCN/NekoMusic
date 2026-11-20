<script setup>
/**
 * AmbientBackdrop —— 环境光背景层
 * ------------------------------------------------------------
 * 页面的装饰性背景（光斑 + 网格），纯装饰、不拦截交互。
 * 由 AppShell 统一提供，页面无需重复实现。
 */
</script>

<template>
  <div class="n-ambient" aria-hidden="true">
    <span class="n-ambient__glow" />
    <span class="n-ambient__grid" />
  </div>
</template>

<style scoped>
.n-ambient {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: var(--n-z-ambient);
  overflow: hidden;
}

.n-ambient__glow {
  position: absolute;
  inset: 0 0 auto;
  height: 360px;
  background:
    linear-gradient(110deg, rgba(240, 181, 110, 0.08), transparent 34%),
    linear-gradient(250deg, rgba(141, 213, 176, 0.06), transparent 42%);
  opacity: 0.9;
}

.n-ambient__grid {
  position: absolute;
  inset: 0;
  opacity: 0.24;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.018) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.014) 1px, transparent 1px);
  background-size: 72px 72px;
  mask-image: linear-gradient(to bottom, black, transparent 72%);
}

@media (prefers-reduced-motion: reduce) {
  .n-ambient__glow { opacity: 0.7; }
}
</style>
