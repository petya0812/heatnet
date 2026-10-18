<script setup lang="ts">
import {SwitchRoot, SwitchThumb} from 'reka-ui';

defineProps<{modelValue: boolean; label?: string; size?: 'sm' | 'md'}>();
defineEmits<{'update:modelValue': [boolean]}>();
</script>

<template>
  <SwitchRoot :model-value="modelValue" :aria-label="label" @update:model-value="$emit('update:modelValue', $event)"
    class="relative inline-flex shrink-0 items-center rounded-full bg-line-strong transition-colors duration-200 data-[state=checked]:bg-accent"
    :class="size === 'sm' ? 'h-[18px] w-8' : 'h-[22px] w-[38px]'">
    <SwitchThumb class="block rounded-full bg-white shadow-[0_1px_3px_rgb(16_24_40/0.3)] transition-transform duration-200 will-change-transform"
      :class="size === 'sm' ? 'size-3.5 translate-x-[2px] data-[state=checked]:translate-x-[16px]' : 'size-[18px] translate-x-[2px] data-[state=checked]:translate-x-[18px]'" />
  </SwitchRoot>
</template>
