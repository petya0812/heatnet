<script setup lang="ts">
import {useRouter} from 'vue-router';
import {ArrowRight, X} from 'lucide-vue-next';
import {clearPicked, picked} from '@/map/engine';
import {useApp} from '@/stores/app';
import {toast} from '@/lib/toast';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiButton from '@/components/ui/UiButton.vue';

const app = useApp();
const router = useRouter();

function openOks(oks: string) {
  if (app.runId) {
    clearPicked();
    router.push({name: 'oks', params: {ds: app.datasetId, run: app.runId, v: app.variantId, oks}});
  } else toast('Откройте результат расчёта, чтобы посмотреть ОКС');
}
</script>

<template>
  <aside v-if="picked" class="panel w-[320px] overflow-hidden" aria-label="Объект на карте" aria-live="polite">
    <div class="flex items-center gap-2.5 px-4 pb-2 pt-3">
      <span class="size-3 shrink-0 rounded-full" :style="{background: picked.color}" aria-hidden="true" />
      <div class="min-w-0 flex-1">
        <div class="eyebrow">{{ picked.kind === 'result' ? 'Результат' : 'Исходные данные' }}</div>
        <h2 class="truncate text-[15px] font-semibold">{{ picked.title }}</h2>
      </div>
      <UiIconButton :icon="X" label="Закрыть" size="sm" @click="clearPicked()" />
    </div>
    <dl class="scroll-thin max-h-[320px] overflow-y-auto px-4 pb-3 text-[13px]">
      <div v-for="r in picked.rows" :key="r.label" class="flex gap-3 border-b border-line py-1.5 last:border-b-0">
        <dt class="w-[48%] shrink-0 text-ink-3">{{ r.label }}</dt>
        <dd class="num min-w-0 break-words text-ink">{{ r.value }}</dd>
      </div>
      <p v-if="!picked.rows.length" class="py-1 text-ink-3">Без атрибутов</p>
    </dl>
    <div v-if="picked.oks || picked.others" class="flex items-center gap-2 border-t border-line px-4 py-2.5">
      <span v-if="picked.others" class="flex-1 text-xs text-ink-3">И ещё {{ picked.others }} под курсором — приблизьте карту</span>
      <span v-else class="flex-1" />
      <UiButton v-if="picked.oks" size="sm" variant="soft" :icon-right="ArrowRight" @click="openOks(picked.oks)">ОКС {{ picked.oks }}</UiButton>
    </div>
  </aside>
</template>
