<script setup lang="ts">
// One editable setting of the calculation: title, what it is, the control and the effect of the chosen value.
// The value lives in the draft of the form (calcDraft), so the field and the form never disagree. The default slot is
// a setting that depends on this one: it is placed under the chosen option.

import {computed} from 'vue';
import {Info, RotateCcw} from 'lucide-vue-next';
import {draftValues as values} from '@/lib/calcDraft';
import UiSegmented from '@/components/ui/UiSegmented.vue';
import UiSlider from '@/components/ui/UiSlider.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';
import UiChoice from '@/components/ui/UiChoice.vue';

const props = defineProps<{e: any; condition?: string}>();

/** Keys with a slider next to the number: the range is the range of the catalogue. */
const SLIDERS = new Set(['turn_penalty', 'odd_joint_turns', 'shape_weight', 'extra_clearance_m', 'min_crossing_angle_deg', 'existing_flow_share', 'entry_slack_m', 'entry_leg_m']);

const e = computed(() => props.e);
const changed = computed(() => String(values.value[e.value.key]) !== String(e.value.default));
const options = computed(() => (e.value.options || []).map((x: any) => ({value: x.value, label: x.label, hint: x.effect})));
/** Options with their own effect read better as a list with the effect of each: it is seen before choosing. */
const asList = computed(() => (e.value.options || []).length > 2 || (e.value.options || []).some((x: any) => x.label.length > 18));
const effect = computed(() => ((e.value.options || []).find((x: any) => String(values.value[e.value.key]) === x.value) || {}).effect);
const isChoice = computed(() => e.value.type === 'enum' || e.value.type === 'boolean');
const isPct = computed(() => e.value.unit === 'доля');
const range = computed(() => (isPct.value ? {min: 0, max: 100, step: 5} : {min: e.value.min, max: e.value.max, step: e.value.step}));
const shown = computed(() => (isPct.value ? Math.round(Number(values.value[e.value.key]) * 100) : Number(values.value[e.value.key])));

const setOption = (raw: string) => { values.value[e.value.key] = e.value.type === 'boolean' ? raw === 'true' : raw; };
function setNumber(raw: unknown) {
  const s = String(raw).replace(',', '.').trim();
  if (s === '' || Number.isNaN(Number(s))) return; // an empty field does not turn into 0
  const v = Math.min(range.value.max, Math.max(range.value.min, Number(s)));
  values.value[e.value.key] = isPct.value ? v / 100 : v;
}
const reset = () => { values.value[e.value.key] = e.value.default; };
</script>

<template>
  <div>
    <p v-if="condition" class="mb-1 text-xs text-ink-3">{{ condition }}</p>
    <div class="flex min-h-6 items-center gap-2">
      <div class="flex min-w-0 flex-1 items-center gap-1.5 text-[13.5px] font-medium text-ink">
        <span v-if="changed" class="size-1.5 shrink-0 rounded-full bg-accent" aria-hidden="true" />
        <span>{{ e.title }}<span v-if="changed" class="sr-only"> (изменено)</span></span>
        <UiTooltip :content="e.description + (e.source ? ` · ${e.source}` : '')">
          <button type="button" class="text-ink-4 hover:text-ink-2" :aria-label="'Что это: ' + e.title"><Info class="size-3.5" aria-hidden="true" /></button>
        </UiTooltip>
      </div>
      <UiTooltip v-if="changed" content="Вернуть значение по умолчанию">
        <button type="button" class="inline-flex h-6 items-center gap-1 rounded-md px-1.5 text-xs font-medium text-accent-ink hover:bg-accent-soft" @click="reset">
          <RotateCcw class="size-3" aria-hidden="true" />Сбросить
        </button>
      </UiTooltip>
    </div>
    <div class="mt-2">
      <UiChoice v-if="isChoice && asList" :model-value="String(values[e.key])" :options="options" :label="e.title" @update:model-value="setOption">
        <template v-if="$slots.default" #checked><slot /></template>
      </UiChoice>
      <UiSegmented v-else-if="isChoice" :model-value="String(values[e.key])" :options="options" :label="e.title" size="sm" @update:model-value="setOption" />
      <div v-else-if="e.type === 'number'" class="flex items-center gap-3">
        <UiSlider v-if="SLIDERS.has(e.key)" class="flex-1" :model-value="shown" v-bind="range" :label="e.title" @update:model-value="setNumber" />
        <label class="flex h-8 shrink-0 items-center overflow-hidden rounded-[10px] border border-line-strong bg-surface-solid focus-within:border-accent focus-within:shadow-[0_0_0_3px_var(--accent-soft)]">
          <input type="number" class="num h-full w-16 bg-transparent px-2 text-right text-[13px] outline-none" :value="shown" v-bind="range" :aria-label="e.title"
            @change="setNumber(($event.target as HTMLInputElement).value); ($event.target as HTMLInputElement).value = String(shown)" />
          <span class="pr-2 text-xs text-ink-3">{{ isPct ? '%' : e.unit }}</span>
        </label>
      </div>
    </div>
    <p v-if="effect && !asList" class="mt-1.5 text-xs leading-snug text-ink-2">{{ effect }}</p>
    <!-- a dependent setting under a choice without its own list: right under the choice, on a shared background -->
    <div v-if="$slots.default && !(isChoice && asList)" class="mt-2 rounded-xl bg-accent-soft px-3 py-2.5"><slot /></div>
    <p v-if="e.source" class="mt-1 text-[11px] text-ink-3">{{ e.source }}</p>
  </div>
</template>
