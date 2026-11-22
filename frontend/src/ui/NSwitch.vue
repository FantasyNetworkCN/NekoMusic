<script setup>
/**
 * NSwitch — 无障碍布尔开关。
 */
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  label: { type: String, default: '' },
  onLabel: { type: String, default: '开启' },
  offLabel: { type: String, default: '关闭' },
})

const emit = defineEmits(['update:modelValue', 'change'])

function onChange(event) {
  const value = event.target.checked
  emit('update:modelValue', value)
  emit('change', value)
}
</script>

<template>
  <label class="n-switch" :class="{ 'n-switch--on': modelValue, 'n-switch--disabled': disabled }">
    <input
      type="checkbox"
      role="switch"
      :checked="modelValue"
      :disabled="disabled"
      :aria-checked="modelValue"
      @change="onChange"
    />
    <span class="n-switch__track"><span class="n-switch__thumb" /></span>
    <span v-if="label || $slots.default" class="n-switch__label">
      <slot>{{ label || (modelValue ? onLabel : offLabel) }}</slot>
    </span>
  </label>
</template>

<style scoped>
.n-switch {
  display: inline-flex;
  align-items: center;
  gap: var(--n-space-3);
  color: var(--n-text-muted);
  cursor: pointer;
  user-select: none;
}

.n-switch input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
}

.n-switch__track {
  position: relative;
  display: inline-block;
  width: 44px;
  height: 24px;
  border: 1px solid var(--n-line);
  border-radius: 999px;
  background: var(--n-surface-soft);
  transition:
    background var(--n-duration-fast) var(--n-ease),
    border-color var(--n-duration-fast) var(--n-ease);
}

.n-switch__thumb {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--n-text-faint);
  transition:
    transform var(--n-duration-fast) var(--n-ease),
    background var(--n-duration-fast) var(--n-ease);
}

.n-switch--on .n-switch__track {
  border-color: var(--n-accent-line);
  background: var(--n-accent-soft);
}

.n-switch--on .n-switch__thumb {
  transform: translateX(20px);
  background: var(--n-accent);
}

.n-switch--disabled {
  cursor: not-allowed;
  opacity: 0.55;
}

.n-switch__label {
  font-size: var(--n-text-sm);
}

.n-switch input:focus-visible + .n-switch__track {
  outline: 2px solid var(--n-accent);
  outline-offset: 2px;
}
</style>
