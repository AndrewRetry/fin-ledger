import { expect, test } from '@playwright/test'

const PAGES = ['/', '/accounts', '/move', '/transactions', '/fx', '/integrity', '/scenarios', '/admin']

for (const width of [768, 1024, 1440]) {
  test(`no overlap and no sideways scrolling at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    for (const path of PAGES) {
      await page.goto(path)
      await page.locator('main h2').first().waitFor()
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth)
      expect(overflow, `${path} scrolls sideways`).toBeLessThanOrEqual(0)
      // <main> spans the full width and pads itself clear of the navbar, so check where the content starts.
      const navbar = await page.locator('nav').first().boundingBox()
      const heading = await page.locator('main h2').first().boundingBox()
      if (navbar && navbar.width > 0 && navbar.x >= 0 && heading) {
        expect(heading.x, `${path}: the navbar covers the page content`).toBeGreaterThanOrEqual(navbar.x + navbar.width)
      }
    }
  })
}
