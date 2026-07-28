/** @type {import('tailwindcss').Config} */
// Tatrman palette (project/common/graphics/tatrman.html), matching the Iris SPA's
// ramps so the two front ends look like one product.
//
// `gray` is overridden rather than extended: the landing page's markup is stock
// Tailwind grays used inline (bg-gray-50, border-gray-200, text-gray-700, …), and
// redirecting the scale rebrands all of them without touching ~30 class names.
// Anchors: gray-400 = Mid Gray, gray-700 = Charcoal, gray-900 = Stage Navy.
//
// NOTE ON YELLOW: it is an accent, not a fill — `bg-primary-500` needs charcoal
// text, never white (white on #FFCB2E is ~1.6:1).
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
        gray: {
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
        },
      },
      fontFamily: {
        sans: ['Inter', 'Calibri', 'Carlito', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}
