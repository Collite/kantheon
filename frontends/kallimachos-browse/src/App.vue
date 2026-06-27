<script setup lang="ts">
import { ref } from "vue";
import PageView from "./components/PageView.vue";
import GraphTraverse from "./components/GraphTraverse.vue";

// The user's OBO bearer (injected by the host shell in real deployments).
const bearer = ref(localStorage.getItem("kallimachos.bearer") ?? "");
const pageId = ref<number | null>(null);
</script>

<template>
  <main style="font-family: system-ui; max-width: 880px; margin: 2rem auto">
    <h1>Kallimachos — wiki browse</h1>
    <label>OBO bearer <input v-model="bearer" style="width: 60%" /></label>
    <hr />
    <GraphTraverse :bearer="bearer" @open="(id) => (pageId = id)" />
    <PageView v-if="pageId !== null" :page-id="pageId" :bearer="bearer" @open="(id) => (pageId = id)" />
  </main>
</template>
