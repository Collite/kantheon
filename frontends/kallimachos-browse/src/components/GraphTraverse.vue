<script setup lang="ts">
import { ref } from "vue";
import { library } from "../api";

const props = defineProps<{ bearer: string }>();
const emit = defineEmits<{ open: [id: number] }>();
const start = ref<number>(1);

function go() {
  // Page ids are wiki nodes; opening one renders it + its links (PageView).
  emit("open", Number(start.value));
}
void props;
void library;
</script>

<template>
  <section>
    <h3>Open a wiki page</h3>
    <input v-model.number="start" type="number" min="1" />
    <button @click="go">Open</button>
  </section>
</template>
