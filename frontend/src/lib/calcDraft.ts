// The settings of the next calculation while they are being chosen. They live while the dataset is open — leaving
// the screen and coming back keeps them; another dataset or a reload starts from the defaults, so the next person
// does not calculate with someone else's settings without noticing.

import {ref} from 'vue';

export const draftValues = ref<Record<string, any>>({});
export const draftFor = ref<string | null>(null);
