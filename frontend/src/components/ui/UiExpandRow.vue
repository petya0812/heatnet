<script setup lang="ts">
import {ref, watch} from 'vue';
import {CollapsibleContent, CollapsibleRoot, CollapsibleTrigger} from 'reka-ui';
import {ChevronRight} from 'lucide-vue-next';

const props = defineProps<{defaultOpen?: boolean}>();
const emit = defineEmits<{open: []}>();
const open = ref(!!props.defaultOpen);
watch(open, (v) => { if (v) emit('open'); });
</script>

<template>
  <CollapsibleRoot v-model:open="open" class="border-b border-line last:border-b-0">
    <CollapsibleTrigger class="group flex w-full items-center gap-2.5 py-2.5 text-left">
      <slot name="lead" />
      <span class="min-w-0 flex-1 text-[13.5px] leading-snug text-ink"><slot name="title" /></span>
      <span class="shrink-0 text-xs text-ink-3"><slot name="meta" /></span>
      <ChevronRight class="size-4 shrink-0 text-ink-4 transition-transform duration-150 group-data-[state=open]:rotate-90" aria-hidden="true" />
    </CollapsibleTrigger>
    <CollapsibleContent class="overflow-hidden">
      <div class="pb-3 pl-[26px] text-[13px] text-ink-2"><slot /></div>
    </CollapsibleContent>
  </CollapsibleRoot>
</template>
