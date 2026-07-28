/**
 * The Tatrman palette — the single source for both the Tailwind config and the
 * PrimeVue preset, so the two can't drift the way `primary-*` and `--p-primary-*`
 * did when each hardcoded its own red scale.
 *
 * Authority: `project/common/graphics/tatrman.html` (Brand exploration v1, 2026-07-10).
 * The five named colours are quoted from it verbatim:
 *
 *   Tatrman Yellow  #FFCB2E → #F2A200   inherited from Collite — "the spotlight"
 *   Charcoal        #4A4B4D             inherited from Collite — structure
 *   Mid Gray        #96989B             descriptors, dividers
 *   Stage Navy      #16283F             product dark surfaces — "the stage"
 *   Ice             #CBDDF4             secondary text on navy
 *
 * The ramps below are interpolations *around* those anchors, not new brand colours:
 * every step exists only so component states (hover/active/subtle fills) have
 * somewhere to go. The anchors are marked inline — treat those as fixed and the
 * rest as adjustable.
 *
 * **Yellow is an accent, not a fill.** The manual's colour logic — "yellow = the
 * living parts; charcoal/white = structure" — is also what accessibility demands:
 * white text on #FFCB2E is ~1.6:1 and unreadable. So the primary *contrast* colour
 * is Charcoal (~5.9:1 on the yellow), and large yellow areas are avoided in favour
 * of charcoal/navy structure with yellow marking the live element.
 */

/** Yellow ramp. 500 and 600 are the two brand anchors; the rest are interpolated. */
export const yellow = {
  50: '#FFF9E8',
  100: '#FFF1C6',
  200: '#FFE79E',
  300: '#FFDC70',
  400: '#FFD34B',
  500: '#FFCB2E', // ← Tatrman Yellow (light stop)
  600: '#F2A200', // ← Tatrman Yellow (deep stop)
  700: '#C98400',
  800: '#A06800',
  900: '#7C5100',
  950: '#4A3000',
} as const

/**
 * Surface ramp: paper → structure → stage. Light steps are neutral greys anchored
 * on Mid Gray; the dark end turns navy because the manual makes the dark surface a
 * *place* ("the stage the marionette performs on"), not merely a dark grey.
 */
export const surface = {
  0: '#FFFFFF',
  50: '#F8F9FA',
  100: '#F1F2F4',
  200: '#E4E6E9',
  300: '#CFD2D6',
  400: '#96989B', // ← Mid Gray
  500: '#74777A',
  600: '#5C5E60',
  700: '#4A4B4D', // ← Charcoal
  800: '#2C3B4C',
  900: '#16283F', // ← Stage Navy
  950: '#0E1A2A',
} as const

/** Named brand constants for the few places that need the colour, not a ramp step. */
export const brand = {
  yellow: '#FFCB2E',
  yellowDeep: '#F2A200',
  charcoal: '#4A4B4D',
  midGray: '#96989B',
  stageNavy: '#16283F',
  ice: '#CBDDF4',
} as const
