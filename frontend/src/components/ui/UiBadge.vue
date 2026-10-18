<script setup lang="ts">
import type {Component} from 'vue';

withDefaults(defineProps<{tone?: 'neutral' | 'accent' | 'ok' | 'warn' | 'bad' | 'outline'; icon?: Component; size?: 'sm' | 'md'; dot?: boolean}>(),
  {tone: 'neutral', size: 'sm'});
const TONES = {
  neutral: 'bg-surface-3 text-ink-2',
  accent: 'bg-accent-soft text-accent-ink',
  ok: 'bg-ok-soft text-ok',
  warn: 'bg-warn-soft text-warn',
  bad: 'bg-bad-soft text-bad',
  outline: 'border border-line-strong text-ink-2',
};
</script>

<template>
  <span class="inline-flex shrink-0 items-center gap-1 rounded-full font-medium whitespace-nowrap"
    :class="[TONES[tone], size === 'md' ? 'h-7 px-2.5 text-[13px]' : 'h-[22px] px-2 text-xs']">
    <span v-if="dot" class="size-1.5 rounded-full bg-current" aria-hidden="true" />
    <component :is="icon" v-if="icon" :class="size === 'md' ? 'size-4' : 'size-3.5'" aria-hidden="true" />
    <slot />
  </span>
</template>
