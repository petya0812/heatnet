<script setup lang="ts">
import type {Component} from 'vue';
import {DropdownMenuContent, DropdownMenuItem, DropdownMenuPortal, DropdownMenuRoot, DropdownMenuSeparator, DropdownMenuTrigger} from 'reka-ui';

export interface MenuItem {
  label: string;
  icon?: Component;
  danger?: boolean;
  disabled?: boolean;
  separator?: boolean;
  onSelect?: () => void;
}
defineProps<{items: MenuItem[]; align?: 'start' | 'end'}>();
</script>

<template>
  <DropdownMenuRoot :modal="false">
    <DropdownMenuTrigger as-child><slot /></DropdownMenuTrigger>
    <DropdownMenuPortal>
      <DropdownMenuContent :align="align || 'end'" :side-offset="6" :collision-padding="8"
        class="z-[180] min-w-[200px] rounded-2xl border border-line bg-surface-solid p-1.5 shadow-pop data-[state=open]:animate-[fade-up_.14s_ease-out]">
        <template v-for="(it, i) in items" :key="i">
          <DropdownMenuSeparator v-if="it.separator" class="mx-2 my-1 h-px bg-line" />
          <DropdownMenuItem v-else :disabled="it.disabled" @select="it.onSelect && it.onSelect()"
            class="flex h-9 cursor-pointer select-none items-center gap-2.5 rounded-[10px] px-2.5 text-[13.5px] outline-none data-[disabled]:opacity-40 data-[highlighted]:bg-surface-3"
            :class="it.danger ? 'text-bad data-[highlighted]:bg-bad-soft' : 'text-ink'">
            <component :is="it.icon" v-if="it.icon" class="size-4 shrink-0" :class="it.danger ? '' : 'text-ink-3'" aria-hidden="true" />
            {{ it.label }}
          </DropdownMenuItem>
        </template>
      </DropdownMenuContent>
    </DropdownMenuPortal>
  </DropdownMenuRoot>
</template>
