import { describe, it, expect } from 'vitest'
import { mountC } from '@/test/mount'
import PortfolioForm from '../PortfolioForm.vue'
import type { Portfolio } from '@/api/types'

const CLIENT_UUID = '11111111-1111-4111-8111-111111111111'
const clients = [{ clientId: CLIENT_UUID, name: 'Acme' }]
const currencies = [{ code: 'CZK', cs: 'Koruna', en: 'Koruna' }]

describe('PortfolioForm', () => {
  it('defaults track_cash ON and emits trackCash true', async () => {
    const w = mountC(PortfolioForm, {
      props: { initial: { clientId: CLIENT_UUID, name: 'Growth', baseCurrency: 'CZK' }, clients, currencies },
    })
    await w.find('form').trigger('submit')
    const emitted = w.emitted('submit') as Array<[Portfolio]> | undefined
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0].trackCash).toBe(true)
  })

  it('blocks submit without a client', async () => {
    const w = mountC(PortfolioForm, { props: { clients, currencies } })
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeFalsy()
    expect(w.find('[data-test=err-client]').exists()).toBe(true)
  })
})
