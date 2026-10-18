<script setup lang="ts">
import {computed} from 'vue';
import {RouterLink} from 'vue-router';
import {CircleHelp, Code, LoaderCircle, WifiOff} from 'lucide-vue-next';
import {useApp} from '@/stores/app';
import {fmt} from '@/lib/format';
import StepNav from './StepNav.vue';
import DatasetSwitcher from './DatasetSwitcher.vue';
import DisplaySettings from './DisplaySettings.vue';
import UiIconButton from '@/components/ui/UiIconButton.vue';
import UiTooltip from '@/components/ui/UiTooltip.vue';
import BrandMark from './BrandMark.vue';

defineEmits<{help: []}>();
const app = useApp();
const busy = computed(() => app.server.running + app.server.queued);
</script>

<template>
  <header class="pointer-events-none absolute inset-x-3 top-3 z-30 flex items-start gap-2 max-md:inset-x-2 max-md:top-2">
    <div class="panel pointer-events-auto flex h-12 min-w-0 shrink items-center gap-1 rounded-full p-1.5">
      <RouterLink to="/" class="flex h-9 shrink-0 items-center gap-2.5 rounded-full pl-1 pr-3 transition-colors hover:bg-surface-3 max-md:pr-1" :class="app.datasetId ? 'max-md:hidden' : ''" aria-label="Трассы подключения — к списку наборов">
        <BrandMark />
        <span class="leading-none max-lg:hidden">
          <span class="block text-[13.5px] font-semibold tracking-[-0.01em] text-ink">Трассы подключения</span>
          <span class="block text-[11px] text-ink-3">к тепловой сети</span>
        </span>
      </RouterLink>
      <template v-if="app.datasetId">
        <span class="h-5 w-px shrink-0 bg-line-strong max-md:hidden" aria-hidden="true" />
        <DatasetSwitcher />
      </template>
    </div>

    <div class="panel pointer-events-auto mx-auto flex h-12 shrink-0 items-center rounded-full px-1.5 max-md:mx-0">
      <StepNav />
    </div>

    <div class="panel pointer-events-auto ml-auto flex h-12 shrink-0 items-center gap-0.5 rounded-full p-1.5 max-md:hidden">
      <UiTooltip v-if="app.server.offline" content="Нет связи с сервером — данные не обновляются" side="bottom">
        <span class="flex h-9 items-center gap-1.5 rounded-full bg-bad-soft px-3 text-[13px] font-medium text-bad" role="status"><WifiOff class="size-4" aria-hidden="true" />Нет связи</span>
      </UiTooltip>
      <UiTooltip v-else-if="busy" :content="`Сейчас считается: ${fmt(app.server.running)}, в очереди: ${fmt(app.server.queued)}`" side="bottom">
        <span class="flex h-9 items-center gap-1.5 rounded-full bg-accent-soft px-3 text-[13px] font-medium text-accent-ink" role="status">
          <LoaderCircle class="size-4 animate-spin" aria-hidden="true" />{{ busy }}
        </span>
      </UiTooltip>
      <DisplaySettings />
      <UiIconButton :icon="CircleHelp" label="Справка: порядок работы, метод, термины" side="bottom" class="rounded-full" @click="$emit('help')" />
      <UiTooltip content="Описание API (Swagger UI), откроется в новой вкладке" side="bottom">
        <a href="swagger-ui.html" target="_blank" rel="noopener" class="flex h-9 items-center gap-1.5 rounded-full px-3 text-[13px] font-medium text-ink-2 hover:bg-surface-3 hover:text-ink">
          <Code class="size-4" aria-hidden="true" />API
        </a>
      </UiTooltip>
    </div>
  </header>
</template>
