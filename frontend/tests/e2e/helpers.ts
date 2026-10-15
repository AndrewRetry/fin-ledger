import { expect, type Page } from '@playwright/test'

export const ALICE = '8398f93a-24c5-3661-8e9a-33d54273d824'

/** Fails the test on any JavaScript error. Logged 4xx/5xx responses are expected (they are being tested). */
export function failOnPageErrors(page: Page) {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(String(error)))
  page.on('console', (message) => {
    if (message.type() === 'error' && !message.text().startsWith('Failed to load resource')) {
      errors.push(message.text())
    }
  })
  return () => expect(errors, 'JavaScript errors on the page').toEqual([])
}

/** The visible tab panel (the Move-money tabs stay mounted, so their fields exist several times). */
export const panel = (page: Page) => page.locator('[role=tabpanel]:visible')

/** Picks an account in a searchable account select. */
export async function pickAccount(page: Page, label: string, name: string) {
  await panel(page).getByRole('combobox', { name: label }).click()
  await page.getByRole('option', { name: new RegExp(`^${name}`) }).click()
}
