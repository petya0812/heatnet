<script setup lang="ts">
import {computed, type Component} from 'vue';
import UiTooltip from './UiTooltip.vue';

const props = withDefaults(defineProps<{
  icon: Component;
  label: string;
  variant?: 'ghost' | 'secondary' | 'soft' | 'danger' | 'primary';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  active?: boolean;
  disabled?: boolean;
  side?: 'top' | 'bottom' | 'left' | 'right';
  href?: string;
  target?: string;
}>(), {variant: 'ghost', size: 'md', side: 'top'});

const cls = computed(() => [
  'inline-flex shrink-0 items-center justify-center transition-colors duration-150 disabled:opacity-40 disabled:pointer-events-none',
  {xs: 'size-7 rounded-lg', sm: 'size-8 rounded-[10px]', md: 'size-9 rounded-xl', lg: 'size-11 rounded-[14px]'}[props.size],
  props.active ? 'bg-accent-soft text-accent-ink' : {
    ghost: 'text-ink-2 hover:bg-surface-3 hover:text-ink',
    secondary: 'bg-surface-solid text-ink-2 border border-line-strong hover:text-ink hover:bg-surface-2 shadow-soft',
    soft: 'bg-surface-2 text-ink-2 hover:bg-surface-3 hover:text-ink',
    danger: 'text-ink-3 hover:bg-bad-soft hover:text-bad',
    primary: 'bg-accent text-on-accent hover:bg-accent-hover',
  }[props.variant],
]);
</script>

<template>
  <UiTooltip :content="label" :side="side">
    <component :is="href ? 'a' : 'button'" :href="href" :target="target" :rel="target ? 'noopener' : undefined"
      :type="href ? undefined : 'button'" :aria-label="label" :aria-pressed="active === undefined || href ? undefined : active" :disabled="disabled" :class="cls">
      <component :is="icon" :class="size === 'xs' ? 'size-3.5' : size === 'lg' ? 'size-5' : 'size-[18px]'" aria-hidden="true" />
    </component>
  </UiTooltip>
</template>
