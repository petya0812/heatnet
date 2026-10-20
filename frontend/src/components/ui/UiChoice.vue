<script setup lang="ts">
import {RadioGroupIndicator, RadioGroupItem, RadioGroupRoot} from 'reka-ui';

// A choice where each option needs its own words: what it does is visible before choosing. A list without frames:
// the chosen option is marked by its background, so the choice does not add one more box inside a group. A setting
// that depends on the chosen option (slot «checked») is shown right under it, on the same background.
defineProps<{modelValue: string; options: {value: string; label: string; hint?: string}[]; label: string; cols?: 1 | 2}>();
const emit = defineEmits<{'update:modelValue': [string]}>();
</script>

<template>
  <RadioGroupRoot :model-value="modelValue" :aria-label="label" class="-mx-2.5 grid gap-0.5" :class="cols === 2 ? 'grid-cols-2' : 'grid-cols-1'"
    @update:model-value="(v) => v != null && emit('update:modelValue', String(v))">
    <template v-for="o in options" :key="o.value">
      <RadioGroupItem :value="o.value"
        class="group flex items-start gap-2.5 rounded-xl px-2.5 py-2 text-left transition-colors data-[state=checked]:bg-accent-soft data-[state=unchecked]:hover:bg-surface-3"
        :class="o.value === modelValue && $slots.checked ? 'rounded-b-none' : ''">
        <span class="mt-[3px] flex size-4 shrink-0 items-center justify-center rounded-full border-[1.5px] border-line-strong transition-colors group-data-[state=checked]:border-accent">
          <RadioGroupIndicator class="size-2 rounded-full bg-accent" />
        </span>
        <span class="min-w-0">
          <span class="block text-[13px] font-medium leading-snug text-ink group-data-[state=checked]:text-accent-ink">{{ o.label }}</span>
          <span v-if="o.hint" class="mt-0.5 block text-xs leading-snug text-ink-3 group-data-[state=checked]:text-ink-2">{{ o.hint }}</span>
        </span>
      </RadioGroupItem>
      <!-- what only matters for the chosen option: under it, on its background, aligned with its words -->
      <div v-if="o.value === modelValue && $slots.checked" class="-mt-0.5 rounded-b-xl bg-accent-soft pb-2.5 pl-9 pr-2.5 pt-1">
        <slot name="checked" />
      </div>
    </template>
  </RadioGroupRoot>
</template>
