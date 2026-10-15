import { expect, test } from '@playwright/test'

import { failOnPageErrors } from './helpers'

test('every scenario passes', async ({ page }) => {
  const noErrors = failOnPageErrors(page)
  await page.goto('/scenarios')
  await page.getByRole('button', { name: 'Run all' }).click()
  await expect(page.getByText('12 / 12 passed')).toBeVisible({ timeout: 60_000 })
  await expect(page.getByText('Failed', { exact: true })).toHaveCount(0)
  noErrors()
})
