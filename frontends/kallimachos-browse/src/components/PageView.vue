<script setup lang="ts">
import { ref, watch } from "vue";
import { library, type Page, type GraphEdge, type GraphNode } from "../api";

const props = defineProps<{ pageId: number; bearer: string }>();
const emit = defineEmits<{ open: [id: number] }>();
const page = ref<Page | null>(null);
const links = ref<{ nodes: GraphNode[]; edges: GraphEdge[] }>({ nodes: [], edges: [] });

async function load() {
  page.value = await library.getPage(props.pageId, props.bearer);
  links.value = await library.traverse(props.pageId, 1, props.bearer);
}
watch(() => props.pageId, load, { immediate: true });
</script>

<template>
  <section v-if="page">
    <h2>{{ page.title }} <small>({{ page.kind }})</small></h2>
    <pre style="white-space: pre-wrap">{{ page.contentMd }}</pre>
    <h3>Links</h3>
    <ul>
      <li v-for="n in links.nodes" :key="n.id">
        <a href="#" @click.prevent="emit('open', n.id)">{{ n.title }} ({{ n.kind }})</a>
      </li>
    </ul>
  </section>
</template>
