<script setup lang="ts">
import UiTooltip from './UiTooltip.vue';

defineProps<{label: string; value: string; unit?: string; hint?: string; tone?: 'neutral' | 'bad' | 'ok' | 'accent'}>();
</script>

<template>
  <UiTooltip :content="hint">
    <div class="inset flex min-w-0 flex-col gap-0.5 px-3 py-2.5" :tabindex="hint ? 0 : undefined">
      <span class="truncate text-xs text-ink-3">{{ label }}</span>
      <span class="num truncate text-[19px] font-semibold leading-tight tracking-tight"
        :class="tone === 'bad' ? 'text-bad' : tone === 'ok' ? 'text-ok' : tone === 'accent' ? 'text-accent-ink' : 'text-ink'">
        {{ value }}<span v-if="unit" class="ml-0.5 text-[13px] font-medium text-ink-3">{{ unit }}</span>
      </span>
    </div>
  </UiTooltip>
</template>
