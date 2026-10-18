<script setup lang="ts">
import {TERMS} from '@/lib/terms';
import UiTooltip from './UiTooltip.vue';

defineProps<{term: string; text?: string}>();
</script>

<template>
  <UiTooltip :content="TERMS[term]"><span class="term" tabindex="0">{{ text || term }}</span></UiTooltip>
</template>
