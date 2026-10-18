<script setup lang="ts">
import {ref, useId} from 'vue';
import {RouterLink, type RouteLocationRaw} from 'vue-router';
import {ArrowLeft} from 'lucide-vue-next';

defineProps<{title: string; eyebrow?: string; back?: {to: RouteLocationRaw; label: string}; flushBody?: boolean}>();
const id = useId();
const scrolled = ref(false);
const body = ref<HTMLElement | null>(null);
const onScroll = (e: Event) => { scrolled.value = (e.target as HTMLElement).scrollTop > 2; };
defineExpose({body});
</script>

<template>
  <section class="flex h-full min-h-0 flex-col" :aria-labelledby="id">
    <header class="relative z-10 shrink-0 px-5 pb-3 pt-4 transition-shadow" :class="scrolled ? 'shadow-[0_1px_0_var(--line),0_8px_16px_-12px_rgb(16_24_40/0.25)]' : ''">
      <RouterLink v-if="back" :to="back.to"
        class="-ml-1.5 mb-1 inline-flex h-7 items-center gap-1 rounded-lg px-1.5 text-[13px] font-medium text-ink-3 transition-colors hover:bg-surface-3 hover:text-ink">
        <ArrowLeft class="size-4" aria-hidden="true" />{{ back.label }}
      </RouterLink>
      <div v-if="eyebrow" class="eyebrow mb-0.5">{{ eyebrow }}</div>
      <div class="flex min-w-0 items-center gap-2">
        <h1 :id="id" class="line-clamp-2 min-w-0 flex-1 text-[20px] font-semibold leading-tight tracking-[-0.01em] text-ink" :title="title">{{ title }}</h1>
        <slot name="title-extra" />
      </div>
      <slot name="sub" />
      <div v-if="$slots.header" class="mt-3"><slot name="header" /></div>
    </header>
    <div ref="body" class="scroll-thin min-h-0 flex-1 overflow-y-auto overscroll-contain" :class="flushBody ? '' : 'space-y-3 px-4 pb-4 pt-1'" @scroll.passive="onScroll">
      <slot />
    </div>
    <footer v-if="$slots.footer" class="flex shrink-0 flex-wrap items-center gap-2 border-t border-line px-4 py-3">
      <slot name="footer" />
    </footer>
  </section>
</template>
