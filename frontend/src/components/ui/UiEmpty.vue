<script setup lang="ts">
import type {Component} from 'vue';

defineProps<{icon?: Component; title: string; text?: string}>();
</script>

<template>
  <div class="flex flex-col items-center px-6 py-10 text-center">
    <div v-if="icon" class="mb-3 flex size-12 items-center justify-center rounded-2xl bg-surface-3 text-ink-3">
      <component :is="icon" class="size-6" aria-hidden="true" />
    </div>
    <div class="text-[15px] font-semibold text-ink">{{ title }}</div>
    <p v-if="text" class="mt-1 max-w-[320px] text-[13.5px] text-ink-3">{{ text }}</p>
    <div v-if="$slots.default" class="mt-4 flex flex-wrap justify-center gap-2"><slot /></div>
  </div>
</template>
