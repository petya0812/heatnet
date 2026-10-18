<script setup lang="ts">
import {computed} from 'vue';
import {useRouter} from 'vue-router';
import {Check, LoaderCircle, TriangleAlert} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {STEPS, useStepStates} from '@/lib/steps';
import UiTooltip from '@/components/ui/UiTooltip.vue';

const app = useApp();
const router = useRouter();
const states = useStepStates();
const current = computed(() => app.step);

function go(id: string) {
  const st = (states.value as any)[id];
  if (!st.enabled && current.value !== id) return;
  const ds = app.datasetId;
  if (id === 'data') return router.push('/');
  if (!ds) return;
  if (id === 'check') return router.push({name: 'check', params: {ds}});
  if (id === 'calc') return router.push({name: 'calc', params: {ds}});
  if (id === 'result') {
    const run = app.runId || app.doneRuns[0]?.id;
    return run ? router.push({name: 'result', params: {ds, run, v: app.runId ? app.variantId : '1'}}) : router.push({name: 'latest', params: {ds}});
  }
}

const tip = (id: string) => {
  const st = (states.value as any)[id];
  if (!st.enabled && current.value !== id) return st.why;
  return {done: 'Готово', working: 'Выполняется', attention: 'Есть замечания'}[st.state as string] || '';
};
</script>

<template>
  <nav aria-label="Шаги работы" class="flex items-center">
    <ol class="flex items-center gap-0.5">
      <li v-for="(s, i) in STEPS" :key="s.id" class="flex items-center">
        <span v-if="i" class="mx-0.5 h-px w-3 bg-line-strong max-lg:w-1.5" aria-hidden="true" />
        <UiTooltip :content="tip(s.id)" side="bottom">
          <button type="button" @click="go(s.id)"
            :aria-current="current === s.id ? 'step' : undefined"
            :aria-disabled="!(states as any)[s.id].enabled && current !== s.id ? 'true' : undefined"
            class="group flex h-9 items-center gap-2 rounded-full pl-1 pr-3 text-[13px] font-medium transition-colors aria-disabled:cursor-not-allowed max-md:pr-1"
            :class="current === s.id ? 'bg-ink text-surface-solid dark:bg-accent dark:text-on-accent' : (states as any)[s.id].enabled ? 'text-ink-2 hover:bg-surface-3 hover:text-ink' : 'text-ink-4'">
            <span class="flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-semibold transition-colors"
              :class="current === s.id ? 'bg-white/15 dark:bg-black/15'
                : (states as any)[s.id].state === 'done' ? 'bg-ok-soft text-ok'
                : (states as any)[s.id].state === 'attention' ? 'bg-warn-soft text-warn'
                : (states as any)[s.id].state === 'working' ? 'bg-accent-soft text-accent' : 'border border-line-strong'">
              <LoaderCircle v-if="(states as any)[s.id].state === 'working'" class="size-3.5 animate-spin" aria-hidden="true" />
              <Check v-else-if="(states as any)[s.id].state === 'done' && current !== s.id" class="size-3.5" stroke-width="2.6" aria-hidden="true" />
              <TriangleAlert v-else-if="(states as any)[s.id].state === 'attention' && current !== s.id" class="size-3.5" aria-hidden="true" />
              <template v-else>{{ s.n }}</template>
            </span>
            <span :class="current === s.id ? '' : 'max-md:sr-only'">{{ s.title }}</span>
          </button>
        </UiTooltip>
      </li>
    </ol>
  </nav>
</template>
