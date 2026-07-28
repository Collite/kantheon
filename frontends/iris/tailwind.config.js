/** @type {import('tailwindcss').Config} */
// Tailwind palette mirrors the PrimeVue preset (src/config/primevue.ts):
//   primary-*  → Tatrman Yellow ramp   (matches `--p-primary-*`)
//   surface-*  → Charcoal / Stage Navy (matches `--p-surface-*`)
// Both are the ramps documented in src/config/brand.ts, whose authority is
// project/common/graphics/tatrman.html. Duplicated as literals here because a
// Tailwind config is loaded by PostCSS outside the app's TS build — keep the two
// in sync; brand.ts carries the reasoning and the anchor annotations.
//
// Migrated components should prefer `--p-*` CSS tokens directly; legacy Tailwind
// classes still resolve to the same shades for cohesion.
//
// NOTE ON YELLOW: it is an accent, not a fill. `bg-primary-500` needs
// `text-surface-700` (charcoal), never white — white on #FFCB2E is ~1.6:1.
export default {
    content: [
        "./index.html",
        "./src/**/*.{vue,js,ts,jsx,tsx}",
    ],
    theme: {
        extend: {
            colors: {
                primary: {
                    50:  '#FFF9E8',
                    100: '#FFF1C6',
                    200: '#FFE79E',
                    300: '#FFDC70',
                    400: '#FFD34B',
                    500: '#FFCB2E', // Tatrman Yellow
                    600: '#F2A200', // Tatrman Yellow, deep stop
                    700: '#C98400',
                    800: '#A06800',
                    900: '#7C5100',
                    950: '#4A3000',
                },
                surface: {
                    0:   '#FFFFFF',
                    50:  '#F8F9FA',
                    100: '#F1F2F4',
                    200: '#E4E6E9',
                    300: '#CFD2D6',
                    400: '#96989B', // Mid Gray
                    500: '#74777A',
                    600: '#5C5E60',
                    700: '#4A4B4D', // Charcoal
                    800: '#2C3B4C',
                    900: '#16283F', // Stage Navy
                    950: '#0E1A2A',
                }
            },
            fontFamily: {
                // Calibri is the wordmark font (ships with Office, full Czech
                // diacritics); Carlito is its metric-compatible free clone, which is
                // what a Linux container will actually have.
                sans: ['Inter', 'Calibri', 'Carlito', 'system-ui', 'sans-serif'],
            },
        },
    },
    plugins: [],
}
