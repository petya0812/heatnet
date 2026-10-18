<script setup lang="ts">
import {computed} from 'vue';

const props = defineProps<{value?: number; max?: number; label?: string; tone?: 'accent' | 'ok' | 'bad'}>();
const pct = computed(() => (props.max ? Math.max(2, Math.min(100, (100 * (props.value || 0)) / props.max)) : null));
</script>

<template>
  <div>
    <div class="h-1.5 overflow-hidden rounded-full bg-line-strong" role="progressbar" :aria-valuenow="pct ?? undefined" aria-valuemin="0" aria-valuemax="100" :aria-label="label">
      <div v-if="pct != null" class="h-full rounded-full transition-[width] duration-500" :class="tone === 'ok' ? 'bg-ok' : tone === 'bad' ? 'bg-bad' : 'bg-accent'" :style="{width: pct + '%'}" />
      <div v-else class="h-full w-1/3 animate-[indeterminate_1.2s_ease-in-out_infinite] rounded-full bg-accent" />
    </div>
    <div v-if="label" class="mt-1.5 text-xs text-ink-3">{{ label }}</div>
  </div>
</template>

<style>
@keyframes indeterminate { from { transform: translateX(-100%); } to { transform: translateX(300%); } }
</style>
