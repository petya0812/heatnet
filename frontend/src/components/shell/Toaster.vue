<script setup lang="ts">
import {CircleAlert, CircleCheck, Info, X} from 'lucide-vue-next';
import {dismiss, toasts} from '@/lib/toast';
</script>

<template>
  <div class="pointer-events-none fixed bottom-4 left-1/2 z-[220] flex w-[min(440px,calc(100vw-24px))] -translate-x-1/2 flex-col items-center gap-2" aria-live="polite" role="status">
    <TransitionGroup enter-from-class="opacity-0 translate-y-2" leave-to-class="opacity-0" enter-active-class="transition duration-200" leave-active-class="transition duration-150">
      <div v-for="t in toasts" :key="t.id"
        class="pointer-events-auto flex w-full items-center gap-2.5 rounded-2xl bg-[#0f172a] py-2.5 pl-3.5 pr-2 text-[13.5px] text-white shadow-pop dark:bg-[#e9edf3] dark:text-[#0f172a]">
        <CircleAlert v-if="t.tone === 'bad'" class="size-[18px] shrink-0 text-[#fb7185] dark:text-[#d61f45]" aria-hidden="true" />
        <CircleCheck v-else-if="t.tone === 'ok'" class="size-[18px] shrink-0 text-[#34d399] dark:text-[#047857]" aria-hidden="true" />
        <Info v-else class="size-[18px] shrink-0 opacity-70" aria-hidden="true" />
        <span class="min-w-0 flex-1">{{ t.text }}</span>
        <button v-if="t.action" type="button" class="shrink-0 rounded-lg px-2.5 py-1 text-[13px] font-semibold text-[#9ab8ff] hover:bg-white/10 dark:text-[#1d4ed8] dark:hover:bg-black/5"
          @click="t.action.run(); dismiss(t.id)">{{ t.action.label }}</button>
        <button type="button" class="shrink-0 rounded-lg p-1 opacity-60 hover:bg-white/10 hover:opacity-100 dark:hover:bg-black/5" aria-label="Закрыть" @click="dismiss(t.id)">
          <X class="size-4" aria-hidden="true" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>
