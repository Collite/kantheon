import { describe, it, expect, vi } from 'vitest'
import { ref } from 'vue'
import { mountC } from '@/test/mount'
import ImportPreview from '../ImportPreview.vue'

const push = vi.fn()
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { loaderRunId: 'r-1' } }),
  useRouter: () => ({ push }),
}))

const screen = ref({
  loaderRun: { loaderRunId: 'r-1' },
  rows: [
    { sourceRowIndex: 0, decision: 'PV_NEW', draft: { kind: 'TX_BUY', quantity: '10' } },
    { sourceRowIndex: 1, decision: 'PV_DUPLICATE', note: 'duplicate of t-9' },
    { sourceRowIndex: 2, decision: 'PV_ERROR', note: 'unknown symbol XYZ' },
  ],
  summary: { newCount: 1, duplicateCount: 1, errorCount: 1 },
})
const commitLoaderRun = vi.fn().mockResolvedValue({ draft_id: 'd-1' })
vi.mock('@/api/loaders', () => ({
  useImportScreen: () => ({ data: screen, isLoading: ref(false) }),
  commitLoaderRun: (...a: unknown[]) => commitLoaderRun(...a),
}))
vi.mock('@/api/client', () => ({ bff: vi.fn().mockResolvedValue([]), BffError: class extends Error {} }))

function mountPreview() {
  return mountC(ImportPreview, { global: { stubs: { Dialog: { template: '<div><slot /></div>' } } } })
}

describe('ImportPreview', () => {
  it('groups rows by decision; commit is enabled by the NEW count (no dead per-row checkbox)', () => {
    const w = mountPreview()
    expect(w.find('[data-test=import-rows-PV_NEW]').exists()).toBe(true)
    expect(w.find('[data-test=import-rows-PV_DUPLICATE]').exists()).toBe(true)
    expect(w.find('[data-test=import-rows-PV_ERROR]').exists()).toBe(true)
    // The loader has no per-row selection, so the misleading include checkbox is gone.
    expect(w.find('[data-test=incl-0]').exists()).toBe(false)
    // 1 NEW row (summary.newCount) → commit enabled.
    expect((w.find('[data-test=import-commit]').element as HTMLButtonElement).disabled).toBe(false)
  })

  it('offers an inline fix on ERROR rows', () => {
    const w = mountPreview()
    expect(w.find('[data-test=fix-2]').exists()).toBe(true)
  })

  it('submits a DRAFT_LOADER_RUN_COMMIT on commit', async () => {
    const w = mountPreview()
    await w.find('[data-test=import-commit]').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(commitLoaderRun).toHaveBeenCalledWith('r-1', true)
  })
})
