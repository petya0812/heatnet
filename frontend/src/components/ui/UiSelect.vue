<script setup lang="ts">
import {SelectContent, SelectIcon, SelectItem, SelectItemIndicator, SelectItemText, SelectPortal, SelectRoot, SelectTrigger, SelectValue, SelectViewport} from 'reka-ui';
import {Check, ChevronsUpDown} from 'lucide-vue-next';

defineProps<{modelValue: string; options: {value: string; label: string; hint?: string}[]; label: string; placeholder?: string; size?: 'sm' | 'md'}>();
defineEmits<{'update:modelValue': [string]}>();
</script>

<template>
  <SelectRoot :model-value="modelValue" @update:model-value="$emit('update:modelValue', String($event))">
    <SelectTrigger :aria-label="label"
      class="inline-flex w-full min-w-0 items-center justify-between gap-2 rounded-xl border border-line-strong bg-surface-solid px-3 text-left text-ink shadow-soft hover:bg-surface-2 data-[placeholder]:text-ink-3"
      :class="size === 'sm' ? 'h-8 text-[13px]' : 'h-10 text-sm'">
      <SelectValue :placeholder="placeholder" class="truncate" />
      <SelectIcon><ChevronsUpDown class="size-4 text-ink-3" aria-hidden="true" /></SelectIcon>
    </SelectTrigger>
    <SelectPortal>
      <SelectContent position="popper" :side-offset="6" :collision-padding="8"
        class="z-[190] max-h-[min(60vh,420px)] min-w-[var(--reka-select-trigger-width)] overflow-hidden rounded-2xl border border-line bg-surface-solid shadow-pop data-[state=open]:animate-[fade-up_.14s_ease-out]">
        <SelectViewport class="scroll-thin p-1.5">
          <SelectItem v-for="o in options" :key="o.value" :value="o.value"
            class="relative flex cursor-pointer select-none flex-col justify-center rounded-[10px] py-2 pl-8 pr-3 text-[13.5px] text-ink outline-none data-[highlighted]:bg-surface-3 data-[state=checked]:font-medium">
            <SelectItemIndicator class="absolute left-2.5 top-1/2 -translate-y-1/2"><Check class="size-4 text-accent" aria-hidden="true" /></SelectItemIndicator>
            <SelectItemText>{{ o.label }}</SelectItemText>
            <span v-if="o.hint" class="text-xs text-ink-3">{{ o.hint }}</span>
          </SelectItem>
        </SelectViewport>
      </SelectContent>
    </SelectPortal>
  </SelectRoot>
</template>
