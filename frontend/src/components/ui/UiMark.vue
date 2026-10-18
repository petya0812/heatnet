<script setup lang="ts">
import {Check, TriangleAlert, X} from 'lucide-vue-next';

defineProps<{tone: 'ok' | 'warn' | 'bad'}>();
</script>

<template>
  <span class="flex size-4 shrink-0 items-center justify-center rounded-full"
    :class="tone === 'ok' ? 'bg-ok-soft text-ok' : tone === 'warn' ? 'bg-warn-soft text-warn' : 'bg-bad-soft text-bad'"
    :aria-label="tone === 'ok' ? 'пройдено' : tone === 'warn' ? 'замечание' : 'ошибка'" role="img">
    <Check v-if="tone === 'ok'" class="size-2.5" stroke-width="3.5" aria-hidden="true" />
    <TriangleAlert v-else-if="tone === 'warn'" class="size-2.5" stroke-width="3" aria-hidden="true" />
    <X v-else class="size-2.5" stroke-width="3.5" aria-hidden="true" />
  </span>
</template>
