<script setup lang="ts">
import {computed} from 'vue';
import {Pause, Play, SkipBack, SkipForward, StepBack, StepForward, X, Route, Clapperboard} from 'lucide-vue-next';
import {onKeyStroke} from '@vueuse/core';
import {close, player, seek, setFilter, stepBack, stepForward, toggle} from '@/map/player';
import {anim, setAnim} from '@/map/anim';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiSlider from '@/components/ui/UiSlider.vue';
import UiSelect from '@/components/ui/UiSelect.vue';
import UiBadge from '@/components/ui/UiBadge.vue';

const filterOptions = computed(() => [{value: 'all', label: 'Весь вариант'}, ...player.oksIds.map((id) => ({value: id, label: 'ОКС ' + id}))]);
const pos = computed({get: () => player.k, set: (v: number) => seek(v)});

const typing = (e: KeyboardEvent) => !!(e.target as HTMLElement).closest('input, textarea, select, [role="slider"], [contenteditable]');
onKeyStroke(' ', (e) => { if (player.open && !typing(e)) { e.preventDefault(); toggle(); } });
onKeyStroke('ArrowRight', (e) => { if (player.open && !typing(e)) stepForward(); });
onKeyStroke('ArrowLeft', (e) => { if (player.open && !typing(e)) stepBack(); });
</script>

<template>
  <section v-if="player.open" class="panel w-full max-w-[640px] px-4 pb-3 pt-3" aria-label="Показ построения" aria-live="polite">
    <div class="flex items-center gap-2">
      <component :is="player.mode === 'search' ? Route : Clapperboard" class="size-[18px] shrink-0 text-accent" aria-hidden="true" />
      <div class="min-w-0 flex-1">
        <div class="truncate text-[14px] font-semibold">{{ player.title || (player.mode === 'search' ? 'Поиск трассы' : 'Построение варианта') }}</div>
        <div v-if="player.subtitle" class="truncate text-xs text-ink-3">{{ player.subtitle }}</div>
      </div>
      <UiBadge v-if="player.live" :tone="player.finished ? 'ok' : 'accent'" dot>{{ player.finished ? 'Расчёт завершён' : 'Идёт расчёт' }}</UiBadge>
      <div v-if="player.mode === 'build' && player.oksIds.length" class="w-[150px] max-sm:hidden">
        <UiSelect :model-value="player.filterOks || 'all'" :options="filterOptions" label="Что показывать" size="sm" @update:model-value="setFilter($event === 'all' ? null : $event)" />
      </div>
      <UiIconButton :icon="X" label="Закрыть показ" size="sm" @click="close()" />
    </div>

    <div class="mt-2 flex items-center gap-1">
      <UiIconButton :icon="SkipBack" label="К началу" size="sm" @click="seek(0)" />
      <UiIconButton :icon="StepBack" label="Шаг назад (←)" size="sm" @click="stepBack()" />
      <UiIconButton :icon="player.playing ? Pause : Play" :label="player.playing ? 'Пауза (пробел)' : 'Пуск (пробел)'" variant="primary" @click="toggle()" />
      <UiIconButton :icon="StepForward" label="Шаг вперёд (→)" size="sm" @click="stepForward()" />
      <UiIconButton :icon="SkipForward" label="В конец" size="sm" @click="seek(player.total)" />
      <div class="mx-2 min-w-0 flex-1"><UiSlider v-model="pos" :min="0" :max="Math.max(1, player.total)" :step="1" label="Шаг показа" /></div>
      <button type="button" class="kbd h-7 min-w-[38px] cursor-pointer hover:bg-surface-3" :aria-label="`Скорость ${anim.speed}×, изменить`"
        @click="setAnim('speed', anim.speed >= 8 ? 0.5 : anim.speed * 2)">{{ anim.speed }}×</button>
    </div>

    <div v-if="player.caption" class="mt-2 rounded-xl bg-surface-2 px-3 py-2 text-[13px] leading-snug">
      <div>
        <span class="text-ink-3">{{ player.caption.step }} · </span>
        <b v-if="player.caption.lead" :class="player.caption.tone === 'bad' ? 'text-bad' : player.caption.tone === 'ok' ? 'text-ok' : 'text-ink'">{{ player.caption.lead }}. </b>
        <span class="text-ink-2">{{ player.caption.text }}</span>
      </div>
      <div class="mt-0.5 text-xs text-ink-3">{{ player.caption.summary }}</div>
    </div>
  </section>
</template>
