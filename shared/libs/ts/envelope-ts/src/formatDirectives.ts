// C/Java-style format-string interpreter for table column `format` directives.
//
// Ported verbatim (behaviour-preserving) from
// ai-platform/frontends/agents-fe/src/components/chat/formats/TableRenderer.vue
// (`applyFormat`). Handles the common cases the agent emits: `%.2f`, `%d`,
// `%05d`, `%.0f%%`, `%s`, plus `%e/%E/%g/%G/%x`. Anything unrecognised falls
// through to `String(value)`.
//
// This is the legacy/printf path (`TableColumnSpec.format`). The locale-aware
// `number: NumberFormatSpec` path stays in the FE renderer (it needs the active
// UI locale via Intl.NumberFormat); only the locale-independent directive
// interpreter is a shared contract helper.

const FORMAT_TOKEN =
  /%(?:(?<flags>[-+ 0#]*)(?<width>\d+)?(?:\.(?<prec>\d+))?)?(?<type>[dfeEgGsx])/g

/** Apply a printf-style format string to a single value. */
export function applyFormat(raw: string, value: unknown): string {
  if (value === null || value === undefined) return ''
  return raw.replace(FORMAT_TOKEN, (...args) => {
    const groups = args[args.length - 1] as {
      flags?: string
      width?: string
      prec?: string
      type: string
    }
    const flags = groups.flags ?? ''
    const width = groups.width ? parseInt(groups.width, 10) : 0
    const prec = groups.prec !== undefined ? parseInt(groups.prec, 10) : -1
    const type = groups.type

    let out = ''
    const num = typeof value === 'number' ? value : Number(value)
    switch (type) {
      case 'd':
        if (Number.isFinite(num)) {
          const sign = num < 0 ? '-' : flags.includes('+') ? '+' : flags.includes(' ') ? ' ' : ''
          out = sign + Math.trunc(Math.abs(num)).toString()
        } else {
          out = String(value)
        }
        break
      case 'f':
        if (Number.isFinite(num)) {
          out = prec >= 0 ? num.toFixed(prec) : num.toString()
        } else {
          out = String(value)
        }
        break
      case 'e':
      case 'E':
        if (Number.isFinite(num)) {
          out = num.toExponential(prec >= 0 ? prec : 6)
          if (type === 'E') out = out.toUpperCase()
        } else {
          out = String(value)
        }
        break
      case 'g':
      case 'G':
        if (Number.isFinite(num)) {
          out = prec >= 0 ? num.toPrecision(prec) : num.toString()
          if (type === 'G') out = out.toUpperCase()
        } else {
          out = String(value)
        }
        break
      case 'x':
        if (Number.isFinite(num)) out = Math.trunc(num).toString(16)
        else out = String(value)
        break
      case 's':
      default:
        out = String(value)
        break
    }
    if (width > out.length) {
      const pad = flags.includes('0') && /[def]/.test(type) ? '0' : ' '
      if (flags.includes('-')) {
        out = out.padEnd(width, pad)
      } else if (pad === '0' && /^[-+ ]/.test(out)) {
        // C/printf: the sign sits OUTSIDE the zero-fill ("-0042", not "00-42").
        // Split off the leading sign, zero-pad the digits to width-1, re-prepend.
        const sign = out[0]
        out = sign + out.slice(1).padStart(width - 1, '0')
      } else {
        out = out.padStart(width, pad)
      }
    }
    return out
  })
}
