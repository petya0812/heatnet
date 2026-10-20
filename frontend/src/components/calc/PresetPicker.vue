<script setup lang="ts">
// Ready sets of settings (`GET /api/params` → `presets`) and the «Автоулучшение» switch. Neither keeps state of its own:
// the chosen preset is the one the values of the draft are equal to, «Свой» — when none; the switch is on when
// `group_rebuild` is on. Choosing a preset replaces all values; «Свой» opens the advanced settings.

import {computed} from 'vue';
import {RadioGroupIndicator, RadioGroupItem, RadioGroupRoot} from 'reka-ui';
import {Sparkles} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {draftValues as values} from '@/lib/calcDraft';
import {fullValues, paramVisible, presetOf} from '@/lib/catalog';
import UiSwitch from '@/components/ui/UiSwitch.vue';

const emit = defineEmits<{custom: []}>();
const app = useApp();

const CUSTOM = {key: 'custom', title: 'Свой', description: 'изменено вручную'};
const presets = computed<any[]>(() => [...(app.catalog?.presets || []), CUSTOM]);
const current = computed(() => presetOf(app.catalog, values.value));

function pick(v: unknown) {
  if (v == null) return;
  if (v === 'custom') { emit('custom'); return; }
  const p = presets.value.find((x) => x.key === v);
  if (p) values.value = fullValues(app.catalog, p.values);
}

const auto = computed(() => app.catalog?.auto_improve);
const rebuild = computed(() => (app.catalog?.params || []).find((e: any) => e.key === 'group_rebuild'));
/** Rebuilding works on a joint scheme only: with separate connection the switch has nothing to do. */
const autoAvailable = computed(() => !rebuild.value || paramVisible(rebuild.value, values.value));
const autoOn = computed(() => autoAvailable.value && values.value.group_rebuild === true);
function setAuto(on: boolean) {
  if (!auto.value) return;
  values.value = {...values.value, ...(on ? auto.value.on : auto.value.off)};
}
</script>

<template>
  <div class="space-y-2">
    <RadioGroupRoot :model-value="current" aria-label="Набор настроек" class="grid grid-cols-2 gap-1.5" @update:model-value="pick">
      <RadioGroupItem v-for="p in presets" :key="p.key" :value="p.key"
        class="group flex min-w-0 items-start gap-2 rounded-xl border border-line bg-surface-solid px-2.5 py-2 text-left transition-colors data-[state=checked]:border-accent/50 data-[state=checked]:bg-accent-soft data-[state=unchecked]:hover:bg-surface-2">
        <span class="mt-[3px] flex size-4 shrink-0 items-center justify-center rounded-full border-[1.5px] border-line-strong transition-colors group-data-[state=checked]:border-accent">
          <RadioGroupIndicator class="size-2 rounded-full bg-accent" />
        </span>
        <span class="min-w-0">
          <span class="block text-[13px] font-medium leading-snug text-ink group-data-[state=checked]:text-accent-ink">{{ p.title }}</span>
          <span class="mt-0.5 block text-xs leading-snug text-ink-3 group-data-[state=checked]:text-ink-2">{{ p.description }}</span>
        </span>
      </RadioGroupItem>
    </RadioGroupRoot>

    <label v-if="auto" class="flex items-center gap-2.5 rounded-xl border border-line bg-surface-solid px-2.5 py-2"
      :class="autoAvailable ? 'cursor-pointer hover:bg-surface-2' : 'opacity-60'">
      <Sparkles class="size-4 shrink-0 text-accent" aria-hidden="true" />
      <span class="min-w-0 flex-1">
        <span class="block text-[13px] font-medium leading-snug text-ink">{{ auto.title }}</span>
        <span class="mt-0.5 block text-xs leading-snug text-ink-3">{{ autoAvailable ? auto.description : 'только при совместном подключении ОКС' }}</span>
      </span>
      <UiSwitch :model-value="autoOn" :label="auto.title" :disabled="!autoAvailable" @update:model-value="setAuto" />
    </label>
  </div>
</template>
