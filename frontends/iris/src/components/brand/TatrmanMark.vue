<script setup lang="ts">
// The Tatrman mark — Concept A, "Na nitích" (project/common/graphics/tatrman.html).
//
// Inlined rather than imported from src/assets/brand/tatrman-mark.svg because the
// build has no SVG-as-component loader, and adding one for a single mark is not
// worth a new dependency. The .svg file remains the canonical asset for everything
// outside this app (favicon, docs, the landing page); keep the two in step.
//
// The structure (control bar + outer rows + wordmark elsewhere) is `currentColor`,
// so the caller sets the colour: charcoal on white, white on Stage Navy. The head,
// the middle row and the string grey are brand constants and never inherit — the
// manual is explicit that yellow marks the living parts and the strings stay grey.
//
// The gradient id is namespaced because several instances can share a document and
// duplicate ids would make one of them silently steal the other's fill.
withDefaults(defineProps<{ gradientId?: string }>(), {
  gradientId: 'tatrman-mark-yellow',
})
</script>

<template>
  <svg viewBox="0 0 240 240" role="img" aria-label="Tatrman" fill="none">
    <defs>
      <linearGradient :id="gradientId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stop-color="#FFCB2E" />
        <stop offset="1" stop-color="#F2A200" />
      </linearGradient>
    </defs>

    <!-- control bar (the model) -->
    <rect
      x="62" y="29.5" width="116" height="13" rx="6.5"
      fill="currentColor" transform="rotate(-7 120 36)"
    />

    <!-- strings (deterministic bindings) — Mid Gray, never yellow -->
    <g stroke="#96989B" stroke-width="2.4" stroke-linecap="round" fill="none">
      <path d="M 78 41.5 L 95 119" />
      <path d="M 120 36.5 L 120 70" />
      <path d="M 162 31.5 L 152 136" />
    </g>

    <!-- head (the one who answers) -->
    <circle cx="120" cy="88" r="17" :fill="`url(#${gradientId})`" />
    <circle cx="113.6" cy="86" r="2.3" fill="#4A4B4D" />
    <circle cx="126.4" cy="86" r="2.3" fill="#4A4B4D" />

    <!-- body: table rows, caught mid-transformation -->
    <rect
      x="93" y="112" width="52" height="13" rx="6.5"
      fill="currentColor" transform="rotate(-5 119 118.5)"
    />
    <rect
      x="90" y="130" width="64" height="13" rx="6.5"
      :fill="`url(#${gradientId})`" transform="rotate(4 122 136.5)"
    />
    <rect
      x="99" y="148" width="44" height="13" rx="6.5"
      fill="currentColor" transform="rotate(-3 121 154.5)"
    />
  </svg>
</template>
