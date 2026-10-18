<script setup lang="ts">
import {DialogClose, DialogContent, DialogDescription, DialogOverlay, DialogPortal, DialogRoot, DialogTitle} from 'reka-ui';
import {X} from 'lucide-vue-next';

withDefaults(defineProps<{open: boolean; title: string; description?: string; size?: 'sm' | 'md' | 'lg'}>(), {size: 'md'});
defineEmits<{'update:open': [boolean]}>();
</script>

<template>
  <DialogRoot :open="open" @update:open="$emit('update:open', $event)">
    <DialogPortal>
      <DialogOverlay class="fixed inset-0 z-[150] bg-[rgb(15_23_42/0.35)] backdrop-blur-[2px] data-[state=open]:animate-[fade_.15s_ease-out]" />
      <DialogContent
        class="fixed left-1/2 top-1/2 z-[160] flex max-h-[min(86vh,760px)] w-[calc(100vw-24px)] -translate-x-1/2 -translate-y-1/2 flex-col rounded-[22px] border border-line bg-surface-solid shadow-pop focus:outline-none data-[state=open]:animate-[pop_.18s_ease-out]"
        :class="{sm: 'max-w-[420px]', md: 'max-w-[560px]', lg: 'max-w-[760px]'}[size]">
        <div class="flex items-start gap-3 px-6 pt-5 pb-3">
          <div class="min-w-0 flex-1">
            <DialogTitle class="text-[17px] font-semibold text-ink">{{ title }}</DialogTitle>
            <DialogDescription v-if="description" class="mt-1 text-[13.5px] text-ink-3">{{ description }}</DialogDescription>
          </div>
          <DialogClose class="-mr-2 -mt-1 inline-flex size-9 items-center justify-center rounded-xl text-ink-3 hover:bg-surface-3 hover:text-ink" aria-label="Закрыть">
            <X class="size-[18px]" aria-hidden="true" />
          </DialogClose>
        </div>
        <div class="scroll-thin min-h-0 flex-1 overflow-y-auto px-6 pb-5"><slot /></div>
        <div v-if="$slots.footer" class="flex flex-wrap items-center justify-end gap-2 border-t border-line px-6 py-4"><slot name="footer" /></div>
      </DialogContent>
    </DialogPortal>
  </DialogRoot>
</template>

<style>
@keyframes fade { from { opacity: 0; } }
@keyframes pop { from { opacity: 0; transform: translate(-50%, -48%) scale(0.97); } }
</style>
