<script setup lang="ts">
import {computed, ref, watch} from 'vue';
import {useRouter} from 'vue-router';
import {PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger} from 'reka-ui';
import {ChevronsUpDown, Check, Search, LayoutGrid} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {GROUPS, splitName, STATUS_RU} from '@/lib/format';

const app = useApp();
const router = useRouter();
const open = ref(false);
const q = ref('');
watch(open, (v) => { if (v) { q.value = ''; app.loadDatasets().catch(() => {}); } });

const current = computed(() => {
  const o = app.currentOverview || app.datasets.find((d) => d.id === app.datasetId);
  return o ? {title: splitName(o.name).title, version: o.version} : null;
});

const groups = computed(() => {
  const needle = q.value.trim().toLowerCase();
  const list = app.datasets.filter((d) => !needle || (d.name || '').toLowerCase().includes(needle));
  return GROUPS.map((g) => ({...g, items: list.filter((d) => splitName(d.name).group === g.key)
    .sort((a, b) => (a.root_id === b.root_id ? a.version - b.version : 0))})).filter((g) => g.items.length);
});

function pick(id: string) {
  open.value = false;
  router.push({name: 'check', params: {ds: id}});
}
</script>

<template>
  <PopoverRoot v-model:open="open">
    <PopoverTrigger
      class="flex h-9 min-w-0 max-w-[260px] items-center gap-2 rounded-full pl-3 pr-2 text-left text-[13.5px] transition-colors hover:bg-surface-3 data-[state=open]:bg-surface-3"
      aria-label="Сменить набор данных">
      <span class="min-w-0 flex-1 truncate font-semibold text-ink">{{ current ? current.title : 'Набор' }}</span>
      <span v-if="current && current.version > 1" class="shrink-0 rounded-full bg-surface-3 px-1.5 text-[11px] font-semibold text-ink-2">в{{ current.version }}</span>
      <ChevronsUpDown class="size-4 shrink-0 text-ink-3" aria-hidden="true" />
    </PopoverTrigger>
    <PopoverPortal>
      <PopoverContent :side-offset="10" align="start" :collision-padding="10"
        class="z-[180] flex max-h-[min(70vh,560px)] w-[340px] flex-col overflow-hidden rounded-[20px] border border-line bg-surface-solid shadow-pop data-[state=open]:animate-[fade-up_.14s_ease-out]">
        <div class="border-b border-line p-2">
          <label class="flex h-10 items-center gap-2 rounded-xl bg-surface-2 px-3">
            <Search class="size-4 text-ink-3" aria-hidden="true" />
            <input v-model="q" class="min-w-0 flex-1 bg-transparent text-sm text-ink outline-none placeholder:text-ink-4" placeholder="Найти набор" aria-label="Найти набор" />
          </label>
        </div>
        <div class="scroll-thin min-h-0 flex-1 overflow-y-auto p-1.5">
          <div v-for="g in groups" :key="g.key" class="mb-1">
            <div class="eyebrow px-2.5 pb-1 pt-2">{{ g.title }}</div>
            <button v-for="d in g.items" :key="d.id" type="button" @click="pick(d.id)"
              class="flex w-full items-center gap-2 rounded-xl px-2.5 py-2 text-left transition-colors hover:bg-surface-3"
              :class="d.id === app.datasetId ? 'bg-accent-soft' : ''">
              <span class="min-w-0 flex-1">
                <span class="block truncate text-[13.5px] font-medium text-ink">{{ splitName(d.name).title }}<span v-if="d.version > 1" class="text-ink-3"> · версия {{ d.version }}</span></span>
                <span class="block text-xs text-ink-3">{{ STATUS_RU[d.status] || d.status }}{{ d.run_count ? ` · расчётов: ${d.run_count}` : '' }}</span>
              </span>
              <Check v-if="d.id === app.datasetId" class="size-4 text-accent" aria-hidden="true" />
            </button>
          </div>
          <p v-if="!groups.length" class="px-3 py-6 text-center text-[13px] text-ink-3">Ничего не найдено</p>
        </div>
        <div class="border-t border-line p-1.5">
          <button type="button" class="flex h-9 w-full items-center gap-2 rounded-xl px-2.5 text-[13.5px] font-medium text-ink-2 hover:bg-surface-3 hover:text-ink"
            @click="open = false; router.push('/')">
            <LayoutGrid class="size-4" aria-hidden="true" />Все наборы и загрузка файла
          </button>
        </div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
