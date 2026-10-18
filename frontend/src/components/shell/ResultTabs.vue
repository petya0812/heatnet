<script setup lang="ts">
import {computed} from 'vue';
import {useRouter} from 'vue-router';
import {Download, GitCompareArrows, Map as MapIcon} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import UiSegmented from '@/components/ui/UiSegmented.vue';

// Comparison and download are not steps but actions on a result: tabs inside the result.
const app = useApp();
const router = useRouter();
const props = defineProps<{runA?: string | null; variantA?: string | null}>();

const value = computed(() => (app.view === 'compare' ? 'compare' : app.view === 'export' ? 'export' : 'result'));
function pick(id: string) {
  const ds = app.datasetId;
  const run = app.runId || props.runA || app.doneRuns[0]?.id;
  const v = app.runId ? app.variantId : props.variantA || '1';
  if (!run) return;
  if (id === 'result') router.push({name: 'result', params: {ds, run, v}});
  if (id === 'export') router.push({name: 'export', params: {ds, run, v}});
  if (id === 'compare') router.push({name: 'compare', params: {ds, a: `${run}:${v}`}});
}
</script>

<template>
  <UiSegmented :model-value="value" label="Раздел результата" block @update:model-value="pick"
    :options="[{value: 'result', label: 'Вариант', icon: MapIcon}, {value: 'compare', label: 'Сравнение', icon: GitCompareArrows}, {value: 'export', label: 'Выгрузка', icon: Download}]" />
</template>
