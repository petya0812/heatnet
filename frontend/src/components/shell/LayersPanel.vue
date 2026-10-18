<script setup lang="ts">
import {computed} from 'vue';
import {X} from 'lucide-vue-next';
import {basemap, dnStops, flowAfter, groups, restrictionTitleMap, setBasemap, setVisible, showFlowAfter, theme, visible} from '@/map/engine';
import {RESTRICTION_COLORS} from '@/map/palette';
import UiSwitch from '@/components/ui/UiSwitch.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';

defineEmits<{close: []}>();
const SECTIONS = [
  ['after', 'Новая сеть'],
  ['change', 'Существующая сеть'],
  ['problems', 'Замечания'],
  ['overlay', 'Слои анализа'],
  ['before', 'Исходные данные'],
];
const sections = computed(() => SECTIONS.map(([key, title]) => ({key, title,
  items: Object.entries(groups).filter(([, g]) => (g.section || 'overlay') === key)})).filter((s) => s.items.length));
const ramp = computed(() => { theme.value; return dnStops(); });
const restrictions = computed(() => Object.entries(restrictionTitleMap)
  .filter(([k]) => RESTRICTION_COLORS[k] && !['heat_network', 'oks', 'oks_existing', 'building'].includes(k)));
</script>

<template>
  <aside class="panel flex max-h-full w-[300px] flex-col overflow-hidden" aria-label="Слои карты">
    <div class="flex items-center gap-2 px-4 pb-2 pt-3">
      <h2 class="flex-1 text-[15px] font-semibold">Слои</h2>
      <UiIconButton :icon="X" label="Закрыть" size="sm" @click="$emit('close')" />
    </div>
    <div class="scroll-thin min-h-0 flex-1 overflow-y-auto px-2 pb-5 [mask-image:linear-gradient(to_bottom,black_calc(100%-28px),transparent)]">
      <p v-if="!sections.length" class="px-2 py-4 text-[13px] text-ink-3">Откройте набор — здесь появятся его слои.</p>
      <div v-for="s in sections" :key="s.key" class="mb-2">
        <div class="eyebrow px-2 pb-1 pt-2">{{ s.title }}</div>
        <template v-for="[id, g] in s.items" :key="id">
          <div v-if="g.display" class="flex items-center gap-3 rounded-xl px-2 py-1.5 pl-[42px]">
            <span class="h-1 w-5 rounded-full" :style="{background: g.sw}" aria-hidden="true" />
            <span class="text-[13px] text-ink-2">{{ g.title }}</span>
          </div>
          <label v-else class="flex cursor-pointer items-start gap-3 rounded-xl px-2 py-1.5 hover:bg-surface-2">
            <span class="mt-1 flex w-5 shrink-0 justify-center" aria-hidden="true">
              <span v-if="g.kind === 'line'" class="mt-1 h-1 w-5 rounded-full" :style="{background: g.sw}" />
              <span v-else-if="g.kind === 'dot'" class="size-3 rounded-full" :style="{background: g.sw, boxShadow: g.stroke ? `0 0 0 2px ${g.stroke}` : ''}" />
              <span v-else class="size-3.5 rounded-[4px]" :style="{background: g.sw, boxShadow: g.stroke ? `inset 0 0 0 1.5px ${g.stroke}` : ''}" />
            </span>
            <span class="min-w-0 flex-1">
              <span class="block text-[13.5px] leading-snug text-ink">{{ g.title }}</span>
              <span v-if="g.hint" class="block text-xs leading-snug text-ink-3">{{ g.hint }}</span>
            </span>
            <UiSwitch :model-value="!!visible[id]" :label="g.title" size="sm" class="mt-0.5" @update:model-value="setVisible(id, $event)" />
          </label>
          <div v-if="g.ramp && visible[id]" class="mb-1 pl-[42px] pr-2">
            <div class="flex h-2 overflow-hidden rounded-full">
              <span v-for="(c, i) in ramp.filter((_, i) => i % 2 === 1)" :key="i" class="flex-1" :style="{background: c as string}" />
            </div>
            <div class="mt-1 flex justify-between text-[11px] text-ink-3">
              <span v-for="(d, i) in ramp.filter((_, i) => i % 2 === 0)" :key="i">{{ d }}</span>
            </div>
          </div>
          <div v-if="g.restrictions && visible[id] && restrictions.length" class="mb-1 flex flex-wrap gap-1 pl-[42px] pr-2">
            <span v-for="[k, v] in restrictions" :key="k" class="inline-flex h-6 items-center gap-1.5 rounded-full bg-surface-2 pl-1.5 pr-2 text-[11.5px] text-ink-2">
              <span class="size-2.5 rounded-full" :style="{background: RESTRICTION_COLORS[k]}" aria-hidden="true" />{{ v }}
            </span>
          </div>
          <div v-if="g.flowToggle && visible[id]" class="mb-1 flex items-center gap-2 pl-[42px] pr-2">
            <span class="text-xs text-ink-3">Расход</span>
            <UiSegmented :model-value="flowAfter ? 'after' : 'before'" label="Расход до или после подключения" size="sm"
              :options="[{value: 'before', label: 'До'}, {value: 'after', label: 'После'}]" @update:model-value="showFlowAfter($event === 'after')" />
          </div>
        </template>
      </div>
      <div class="eyebrow px-2 pb-1 pt-2">Подложка</div>
      <label class="flex cursor-pointer items-start gap-3 rounded-xl px-2 py-1.5 hover:bg-surface-2">
        <span class="mt-1 flex w-5 justify-center" aria-hidden="true"><span class="size-3.5 rounded-[4px] bg-line-strong" /></span>
        <span class="min-w-0 flex-1">
          <span class="block text-[13.5px] text-ink">OpenStreetMap</span>
          <span class="block text-xs text-ink-3">нужен интернет</span>
        </span>
        <UiSwitch :model-value="basemap" label="Подложка OpenStreetMap" size="sm" class="mt-0.5" @update:model-value="setBasemap($event)" />
      </label>
    </div>
  </aside>
</template>
