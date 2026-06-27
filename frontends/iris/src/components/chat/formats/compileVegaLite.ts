import type { ChartIntent } from '@/types/envelope'

const PRIMARY_COLOR = '#3B82F6'

function legend(show: boolean): { title: null } | null {
  return show ? { title: null } : null
}

export function compileVegaLite(
  intent: ChartIntent,
  data: unknown[],
): Record<string, unknown> {
  const showLegend = intent.showLegend !== false
  const stacked = intent.stacked ?? false
  const hidden = new Set(intent.hideSeries ?? [])
  const visibleY = intent.y.filter((f) => !hidden.has(f))
  const effectiveY = visibleY.length > 0 ? visibleY : intent.y.slice(0, 1)

  const base: Record<string, unknown> = {
    $schema: 'https://vega.github.io/schema/vega-lite/v5.json',
    data: { values: data },
  }
  if (intent.title) base.title = intent.title

  switch (intent.kind) {
    case 'line':
      return effectiveY.length === 1
        ? {
            ...base,
            mark: { type: 'line', color: PRIMARY_COLOR },
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: effectiveY[0], type: 'quantitative', title: effectiveY[0] },
            },
          }
        : {
            ...base,
            mark: { type: 'line' },
            transform: [{ fold: effectiveY, as: ['series', 'value'] }],
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: 'value', type: 'quantitative' },
              color: { field: 'series', type: 'nominal', legend: legend(showLegend) },
            },
          }

    case 'bar':
      return effectiveY.length === 1
        ? {
            ...base,
            mark: { type: 'bar', color: PRIMARY_COLOR },
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: effectiveY[0], type: 'quantitative', stack: stacked ? 'zero' : null },
            },
          }
        : {
            ...base,
            mark: { type: 'bar' },
            transform: [{ fold: effectiveY, as: ['series', 'value'] }],
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: 'value', type: 'quantitative', stack: stacked ? 'zero' : null },
              color: { field: 'series', type: 'nominal', legend: legend(showLegend) },
            },
          }

    case 'pie':
      return {
        ...base,
        mark: { type: 'arc' },
        encoding: {
          theta: { field: intent.y[0], type: 'quantitative' },
          color: { field: intent.x, type: 'nominal', legend: legend(showLegend) },
        },
      }

    case 'area':
      return effectiveY.length === 1
        ? {
            ...base,
            mark: { type: 'area', color: PRIMARY_COLOR },
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: effectiveY[0], type: 'quantitative', stack: stacked ? 'zero' : null },
            },
          }
        : {
            ...base,
            mark: { type: 'area' },
            transform: [{ fold: effectiveY, as: ['series', 'value'] }],
            encoding: {
              x: { field: intent.x, type: 'nominal', axis: { labelAngle: 0 } },
              y: { field: 'value', type: 'quantitative', stack: stacked ? 'zero' : null },
              color: { field: 'series', type: 'nominal', legend: legend(showLegend) },
            },
          }

    case 'scatter':
      return effectiveY.length === 1
        ? {
            ...base,
            mark: { type: 'point', color: PRIMARY_COLOR },
            encoding: {
              x: { field: intent.x, type: 'quantitative' },
              y: { field: effectiveY[0], type: 'quantitative' },
            },
          }
        : {
            ...base,
            mark: { type: 'point' },
            transform: [{ fold: effectiveY, as: ['series', 'value'] }],
            encoding: {
              x: { field: intent.x, type: 'quantitative' },
              y: { field: 'value', type: 'quantitative' },
              color: { field: 'series', type: 'nominal', legend: legend(showLegend) },
            },
          }

    default:
      return base
  }
}
