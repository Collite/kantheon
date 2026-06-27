import { describe, it, expect } from 'vitest'
import { mountC } from '@/test/mount'
import ClientForm from '../ClientForm.vue'
import type { Client } from '@/api/types'

describe('ClientForm', () => {
  it('blocks submit and flags the field when the email is invalid (Zod, inline)', async () => {
    const w = mountC(ClientForm)
    await w.find('[data-test=client-name]').setValue('Acme Corp')
    await w.find('[data-test=client-email]').setValue('not-an-email')
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeFalsy()
    expect(w.find('[data-test=err-email]').exists()).toBe(true)
  })

  it('emits a camelCase Client on valid input', async () => {
    const w = mountC(ClientForm)
    await w.find('[data-test=client-name]').setValue('Acme Corp')
    await w.find('[data-test=client-email]').setValue('ops@acme.test')
    await w.find('form').trigger('submit')
    const emitted = w.emitted('submit') as Array<[Client]> | undefined
    expect(emitted).toBeTruthy()
    expect(emitted![0]![0].name).toBe('Acme Corp')
    expect(emitted![0]![0].contactEmail).toBe('ops@acme.test')
  })

  it('requires a name', async () => {
    const w = mountC(ClientForm)
    await w.find('form').trigger('submit')
    expect(w.emitted('submit')).toBeFalsy()
    expect(w.find('[data-test=err-name]').exists()).toBe(true)
  })
})
