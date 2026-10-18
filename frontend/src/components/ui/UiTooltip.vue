<script setup lang="ts">
import {TooltipArrow, TooltipContent, TooltipPortal, TooltipRoot, TooltipTrigger} from 'reka-ui';

withDefaults(defineProps<{content?: string; side?: 'top' | 'bottom' | 'left' | 'right'; delay?: number}>(), {side: 'top', delay: 250});
</script>

<template>
  <TooltipRoot v-if="content || $slots.content" :delay-duration="delay">
    <TooltipTrigger as-child><slot /></TooltipTrigger>
    <TooltipPortal>
      <TooltipContent :side="side" :side-offset="6" :collision-padding="8"
        class="z-[200] max-w-[300px] rounded-[10px] bg-[#0f172a] px-3 py-2 text-[12.5px] leading-snug text-white shadow-pop data-[state=delayed-open]:animate-[fade-up_.12s_ease-out] dark:bg-[#e9edf3] dark:text-[#0f172a]">
        <slot name="content">{{ content }}</slot>
        <TooltipArrow class="fill-[#0f172a] dark:fill-[#e9edf3]" :width="10" :height="5" />
      </TooltipContent>
    </TooltipPortal>
  </TooltipRoot>
  <slot v-else />
</template>
