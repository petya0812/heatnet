<script setup lang="ts">
import {RadioGroupIndicator, RadioGroupItem, RadioGroupRoot} from 'reka-ui';

// A choice where each option needs its own words: what it does is visible before choosing.
defineProps<{modelValue: string; options: {value: string; label: string; hint?: string}[]; label: string; cols?: 1 | 2}>();
const emit = defineEmits<{'update:modelValue': [string]}>();
</script>

<template>
  <RadioGroupRoot :model-value="modelValue" :aria-label="label" class="grid gap-1.5" :class="cols === 2 ? 'grid-cols-2' : 'grid-cols-1'"
    @update:model-value="(v) => v != null && emit('update:modelValue', String(v))">
    <RadioGroupItem v-for="o in options" :key="o.value" :value="o.value"
      class="group flex items-start gap-2.5 rounded-xl border border-line bg-surface-solid px-3 py-2 text-left transition-[border-color,box-shadow,background-color] hover:border-line-strong data-[state=checked]:border-accent data-[state=checked]:shadow-[0_0_0_3px_var(--accent-soft)]">
      <span class="mt-[3px] flex size-4 shrink-0 items-center justify-center rounded-full border-[1.5px] border-line-strong transition-colors group-data-[state=checked]:border-accent">
        <RadioGroupIndicator class="size-2 rounded-full bg-accent" />
      </span>
      <span class="min-w-0">
        <span class="block text-[13px] font-medium leading-snug text-ink">{{ o.label }}</span>
        <span v-if="o.hint" class="mt-0.5 block text-xs leading-snug text-ink-3">{{ o.hint }}</span>
      </span>
    </RadioGroupItem>
  </RadioGroupRoot>
</template>
