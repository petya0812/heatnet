<script setup lang="ts">
import {PopoverAnchor, PopoverContent, PopoverPortal, PopoverRoot, PopoverTrigger} from 'reka-ui';
import {Settings2, Sun, Moon, Monitor} from 'lucide-vue-next';
import {anim, resetAnim, reducedMotion, setAnim} from '@/map/anim';
import {themeMode} from '@/lib/theme';
import UiSwitch from '@/components/ui/UiSwitch.vue';
import UiSegmented from '@/components/ui/UiSegmented.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';
import UiButton from '@/components/ui/UiButton.vue';

const OPTS: {key: 'build' | 'live' | 'transitions' | 'flow'; label: string; hint: string}[] = [
  {key: 'build', label: 'Проигрывать построение', hint: 'Кнопка «Построение» сразу запускает показ'},
  {key: 'live', label: 'Показывать построение во время расчёта', hint: 'Сеть растёт на карте, пока идёт расчёт'},
  {key: 'transitions', label: 'Плавная смена вариантов', hint: 'Общие участки остаются, разные — растворяются'},
  {key: 'flow', label: 'Анимация потока по новой сети', hint: 'Движение от врезок к ОКС'},
];
</script>

<template>
  <PopoverRoot>
    <!-- an anchor of its own: inside the tooltip the trigger does not reach the popover as its anchor, and the panel
         would stay off screen -->
    <PopoverAnchor class="inline-flex">
      <UiTooltip content="Вид и анимация" side="bottom">
        <PopoverTrigger class="inline-flex size-9 items-center justify-center rounded-full text-ink-2 transition-colors hover:bg-surface-3 hover:text-ink data-[state=open]:bg-surface-3" aria-label="Вид и анимация">
          <Settings2 class="size-[18px]" aria-hidden="true" />
        </PopoverTrigger>
      </UiTooltip>
    </PopoverAnchor>
    <PopoverPortal>
      <PopoverContent :side-offset="10" align="end" :collision-padding="10"
        class="z-[180] w-[330px] rounded-[20px] border border-line bg-surface-solid p-4 shadow-pop data-[state=open]:animate-[fade-up_.14s_ease-out]">
        <div class="mb-2 text-[15px] font-semibold">Вид</div>
        <UiSegmented v-model="themeMode" label="Тема" block
          :options="[{value: 'light', label: 'Светлая', icon: Sun}, {value: 'dark', label: 'Тёмная', icon: Moon}, {value: 'system', label: 'Как в системе', icon: Monitor}]" />
        <div class="mb-1 mt-4 text-[15px] font-semibold">Анимация</div>
        <p v-if="reducedMotion" class="mb-2 text-xs text-ink-3">В системе включено «уменьшить движение» — показ построения по умолчанию выключен.</p>
        <label v-for="o in OPTS" :key="o.key" class="flex cursor-pointer items-start gap-3 rounded-xl py-2">
          <span class="min-w-0 flex-1">
            <span class="block text-[13.5px] text-ink">{{ o.label }}</span>
            <span class="block text-xs text-ink-3">{{ o.hint }}</span>
          </span>
          <UiSwitch :model-value="!!anim[o.key]" :label="o.label" @update:model-value="setAnim(o.key, $event)" />
        </label>
        <div class="mt-2 flex items-center gap-3">
          <span class="flex-1 text-[13.5px]">Скорость показа</span>
          <UiSegmented :model-value="String(anim.speed)" label="Скорость показа" size="sm"
            :options="[0.5, 1, 2, 4, 8].map((x) => ({value: String(x), label: x + '×'}))" @update:model-value="setAnim('speed', Number($event))" />
        </div>
        <div class="mt-4 flex justify-end"><UiButton size="sm" variant="ghost" @click="resetAnim()">По умолчанию</UiButton></div>
      </PopoverContent>
    </PopoverPortal>
  </PopoverRoot>
</template>
