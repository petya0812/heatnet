<script setup lang="ts">
import {computed} from 'vue';
import {SliderRange, SliderRoot, SliderThumb, SliderTrack} from 'reka-ui';

const props = defineProps<{modelValue: number; min: number; max: number; step: number; label: string}>();
const emit = defineEmits<{'update:modelValue': [number]}>();
const model = computed({get: () => [props.modelValue], set: (v: number[]) => emit('update:modelValue', v[0])});
</script>

<template>
  <SliderRoot v-model="model" :min="min" :max="max" :step="step" class="relative flex h-6 w-full touch-none select-none items-center">
    <SliderTrack class="relative h-1.5 grow rounded-full bg-line-strong">
      <SliderRange class="absolute h-full rounded-full bg-accent" />
    </SliderTrack>
    <SliderThumb :aria-label="label"
      class="block size-[18px] rounded-full border-2 border-accent bg-surface-solid shadow-[0_1px_4px_rgb(16_24_40/0.25)] transition-transform hover:scale-110 focus-visible:scale-110" />
  </SliderRoot>
</template>
