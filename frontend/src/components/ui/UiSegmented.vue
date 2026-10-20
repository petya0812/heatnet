<script setup lang="ts">
import type {Component} from 'vue';
import {ToggleGroupItem, ToggleGroupRoot} from 'reka-ui';
import UiTooltip from './UiTooltip.vue';

const props = withDefaults(defineProps<{
  modelValue: string;
  options: {value: string; label: string; icon?: Component; hint?: string; disabled?: boolean; count?: number}[];
  label: string;
  size?: 'sm' | 'md';
  block?: boolean;
}>(), {size: 'md'});
const emit = defineEmits<{'update:modelValue': [string]}>();

// a segmented control always has a value: a second click on the chosen item does not clear it.
// The chosen item is styled by aria-pressed: a tooltip around an item (as-child) puts its own data-state on it.
const pick = (v: unknown) => { if (typeof v === 'string' && v && v !== props.modelValue) emit('update:modelValue', v); };
</script>

<template>
  <ToggleGroupRoot type="single" :model-value="modelValue" :aria-label="label" @update:model-value="pick"
    class="inline-flex max-w-full flex-wrap gap-0.5 rounded-xl bg-seg-track p-[3px]" :class="block ? 'flex w-full' : ''">
    <UiTooltip v-for="o in options" :key="o.value" :content="o.hint">
      <ToggleGroupItem :value="o.value" :disabled="o.disabled"
        class="inline-flex min-w-0 items-center justify-center gap-1.5 rounded-[9px] font-medium text-ink-3 transition-all duration-150 hover:text-ink disabled:opacity-40 aria-pressed:bg-seg-on aria-pressed:text-ink aria-pressed:shadow-[0_1px_2px_rgb(16_24_40/0.12),0_0_0_1px_var(--line)]"
        :class="[size === 'sm' ? 'h-7 px-2.5 text-xs' : 'h-8 px-3 text-[13px]', block ? 'flex-1' : '']">
        <component :is="o.icon" v-if="o.icon" class="size-3.5" aria-hidden="true" />
        <span class="truncate">{{ o.label }}</span>
        <span v-if="o.count != null" class="rounded-full bg-surface-3 px-1.5 text-[11px] font-semibold leading-[18px] text-ink-3">{{ o.count }}</span>
      </ToggleGroupItem>
    </UiTooltip>
  </ToggleGroupRoot>
</template>
