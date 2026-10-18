<script setup lang="ts">
import {ref, watch, type Component} from 'vue';
import {CollapsibleContent, CollapsibleRoot, CollapsibleTrigger} from 'reka-ui';
import {ChevronDown} from 'lucide-vue-next';
import {prefs} from '@/lib/prefs';

const props = withDefaults(defineProps<{
  title: string;
  icon?: Component;
  meta?: string;
  tone?: 'neutral' | 'ok' | 'warn' | 'bad' | 'accent';
  defaultOpen?: boolean;
  persist?: string;
  collapsible?: boolean;
  open?: boolean;
  flush?: boolean;
}>(), {defaultOpen: true, collapsible: true, tone: 'neutral', open: undefined});

const isOpen = ref(props.persist ? prefs.get('section.' + props.persist, props.defaultOpen) : props.defaultOpen);
watch(() => props.open, (v) => { if (v !== undefined) isOpen.value = v; }, {immediate: true});
watch(isOpen, (v) => { if (props.persist) prefs.set('section.' + props.persist, v); });
const TONE = {neutral: 'text-ink-3', ok: 'text-ok', warn: 'text-warn', bad: 'text-bad', accent: 'text-accent'};
</script>

<template>
  <CollapsibleRoot v-model:open="isOpen" :disabled="!collapsible" class="card overflow-hidden">
    <CollapsibleTrigger
      class="group flex w-full items-center gap-2.5 px-4 py-3 text-left transition-colors"
      :class="collapsible ? 'hover:bg-surface-2' : 'cursor-default'">
      <component :is="icon" v-if="icon" class="size-[18px] shrink-0" :class="TONE[tone]" aria-hidden="true" />
      <span class="min-w-0 flex-1 truncate text-[14px] font-semibold text-ink">{{ title }}</span>
      <slot name="meta"><span v-if="meta" class="shrink-0 text-xs text-ink-3">{{ meta }}</span></slot>
      <ChevronDown v-if="collapsible" class="size-4 shrink-0 text-ink-4 transition-transform duration-200 group-data-[state=open]:rotate-180" aria-hidden="true" />
    </CollapsibleTrigger>
    <CollapsibleContent class="overflow-hidden data-[state=closed]:animate-[collapse_.18s_ease-out] data-[state=open]:animate-[expand_.2s_ease-out]">
      <div :class="flush ? 'pb-1' : 'px-4 pb-4'"><slot /></div>
    </CollapsibleContent>
  </CollapsibleRoot>
</template>

<style>
@keyframes expand { from { height: 0; opacity: 0; } to { height: var(--reka-collapsible-content-height); opacity: 1; } }
@keyframes collapse { from { height: var(--reka-collapsible-content-height); } to { height: 0; opacity: 0; } }
</style>
