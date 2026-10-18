<script setup lang="ts">
import {nextTick, ref, watch} from 'vue';
import {closeDialog, dialogState} from '@/lib/dialogs';
import UiDialog from '@/components/ui/UiDialog.vue';
import UiButton from '@/components/ui/UiButton.vue';

const input = ref<HTMLInputElement | HTMLTextAreaElement | null>(null);
watch(() => dialogState.open, async (v) => {
  if (v && dialogState.kind === 'prompt') {
    await nextTick();
    setTimeout(() => input.value?.focus(), 30);
  }
});
const cancel = () => closeDialog(dialogState.kind === 'confirm' ? false : null);
const ok = () => closeDialog(dialogState.kind === 'confirm' ? true : dialogState.value);
</script>

<template>
  <UiDialog :open="dialogState.open" :title="dialogState.title" :description="dialogState.text || undefined" size="sm" @update:open="(v) => !v && cancel()">
    <form v-if="dialogState.kind === 'prompt'" @submit.prevent="ok">
      <textarea v-if="dialogState.multiline" ref="input" v-model="dialogState.value" class="input" rows="4" :placeholder="dialogState.placeholder" :aria-label="dialogState.title" />
      <input v-else ref="input" v-model="dialogState.value" class="input" :placeholder="dialogState.placeholder" :aria-label="dialogState.title" />
    </form>
    <template #footer>
      <UiButton variant="ghost" @click="cancel">Отмена</UiButton>
      <UiButton :variant="dialogState.danger ? 'danger' : 'primary'" @click="ok">{{ dialogState.ok }}</UiButton>
    </template>
  </UiDialog>
</template>
