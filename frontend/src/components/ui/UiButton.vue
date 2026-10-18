<script setup lang="ts">
import {computed, type Component} from 'vue';
import {LoaderCircle} from 'lucide-vue-next';

const props = withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger' | 'soft';
  size?: 'xs' | 'sm' | 'md' | 'lg';
  icon?: Component;
  iconRight?: Component;
  loading?: boolean;
  href?: string;
  download?: string;
  target?: string;
  block?: boolean;
  disabled?: boolean;
  type?: 'button' | 'submit';
}>(), {variant: 'secondary', size: 'md', type: 'button'});

const VARIANTS = {
  primary: 'bg-accent text-on-accent hover:bg-accent-hover shadow-[inset_0_1px_0_rgb(255_255_255/0.18),0_1px_2px_rgb(16_24_40/0.18)]',
  secondary: 'bg-surface-solid text-ink border border-line-strong hover:bg-surface-2 shadow-soft',
  soft: 'bg-accent-soft text-accent-ink hover:bg-[color-mix(in_srgb,var(--accent-soft),var(--accent)_10%)]',
  ghost: 'text-ink-2 hover:bg-surface-3 hover:text-ink',
  danger: 'bg-bad-soft text-bad hover:bg-bad hover:text-white',
};
const SIZES = {
  xs: 'h-7 px-2.5 text-xs rounded-lg gap-1',
  sm: 'h-8 px-3 text-[13px] rounded-[10px] gap-1.5',
  md: 'h-10 px-4 text-sm rounded-xl gap-2',
  lg: 'h-12 px-5 text-[15px] rounded-[14px] gap-2',
};
const iconSize = computed(() => (props.size === 'lg' ? 'size-5' : props.size === 'xs' ? 'size-3.5' : 'size-4'));
const cls = computed(() => [
  'inline-flex select-none items-center justify-center font-medium whitespace-nowrap transition-[background-color,color,box-shadow,opacity] duration-150',
  'disabled:opacity-45 disabled:pointer-events-none aria-disabled:opacity-45 aria-disabled:pointer-events-none',
  VARIANTS[props.variant], SIZES[props.size], props.block ? 'w-full' : '',
]);
</script>

<template>
  <component :is="href ? 'a' : 'button'" :href="href" :download="download" :target="target" :rel="target ? 'noopener' : undefined"
    :type="href ? undefined : type" :disabled="href ? undefined : disabled || loading" :aria-disabled="href && disabled ? 'true' : undefined" :class="cls">
    <LoaderCircle v-if="loading" class="animate-spin" :class="iconSize" aria-hidden="true" />
    <component :is="icon" v-else-if="icon" :class="iconSize" aria-hidden="true" />
    <slot />
    <component :is="iconRight" v-if="iconRight" :class="iconSize" aria-hidden="true" />
  </component>
</template>
