import { expect, test } from '@playwright/test'

import { ALICE, failOnPageErrors, panel, pickAccount } from './helpers'

test('overview shows the live chain head and the demo accounts', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto('/')
  await expect(page.getByText('Hash chain length')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Alice' })).toBeVisible()
  await expect(page.getByText('Writes open')).toBeVisible()
  noErrors()
})

test('create an account and see server validation on the form', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto('/accounts')
  await page.getByRole('button', { name: 'New account' }).click()
  await page.getByLabel('Name').fill('   ')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByText('must not be blank')).toBeVisible()
  await page.getByLabel('Name').fill('E2E Dana')
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page).toHaveURL(/\/accounts\/[0-9a-f-]{36}$/)
  await expect(page.getByText('No transactions yet')).toBeVisible()
  noErrors()
})

test('deposit, replay with the same key, and refuse the same key with another body', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto(`/move?tab=deposit&account=${ALICE}`)
  await panel(page).getByLabel('Amount', { exact: true }).fill('12.34')
  await expect(panel(page).getByText('= 1234 minor units')).toBeVisible()
  await panel(page).getByRole('button', { name: 'Deposit' }).click()
  await expect(panel(page).getByText('POSTED')).toBeVisible()
  await panel(page).getByRole('button', { name: 'Send again (same key)' }).click()
  await expect(panel(page).getByText('Idempotent replay')).toBeVisible()
  await panel(page).getByLabel('Amount', { exact: true }).fill('1')
  await panel(page).getByRole('button', { name: 'Deposit' }).click()
  await expect(panel(page).getByText('IDEMPOTENCY_KEY_REUSED')).toBeVisible()
  noErrors()
})

test('cross-currency transfer through the house wallets, then reverse it', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto('/move?tab=transfer')
  await pickAccount(page, 'From', 'Alice')
  await pickAccount(page, 'To', 'Carol')
  await panel(page).getByLabel('Amount', { exact: true }).fill('10')
  await expect(panel(page).getByText('The recipient gets about')).toBeVisible()
  await panel(page).getByRole('button', { name: 'Transfer' }).click()
  await expect(panel(page).getByText('FX TRANSFER')).toBeVisible()
  await expect(panel(page).locator('tbody tr')).toHaveCount(4)

  await panel(page).getByRole('link', { name: 'Open' }).click()
  await expect(page.getByText('Hash verified in your browser')).toBeVisible()
  await page.getByRole('button', { name: 'Reverse' }).first().click()
  await page.getByLabel('Reason').fill('e2e')
  await page.getByRole('dialog').getByRole('button', { name: 'Reverse' }).click()
  await expect(page.getByText('REVERSAL', { exact: true })).toBeVisible()
  await expect(page.getByText('Hash verified in your browser')).toBeVisible()
  noErrors()
})

test('integrity checks come back clean', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto('/integrity')
  await page.getByRole('button', { name: 'Run integrity checks' }).click()
  await expect(panel(page).getByText('Every link verified')).toBeVisible()
  await expect(panel(page).getByText('none found')).toHaveCount(7)
  noErrors()
})
