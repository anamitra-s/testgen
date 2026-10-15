import { test, expect } from '@playwright/test';

test('Dashboard date filter persists across reload', async ({ page }) => {
  await page.goto('https://app.example.com/dashboard');

  // Open date range filter modal
  await page.getByTestId('date-filter-button').click(); // ref: date-filter-btn

  // Select start date
  await page.getByLabel('Start Date').fill('2026-03-01'); // ref: start-date-input

  // Select end date
  await page.getByLabel('End Date').fill('2026-03-14'); // ref: end-date-input

  // Apply filter
  await page.getByRole('button', { name: 'Apply Filter' }).click(); // ref: apply-filter-btn

  // Assert filter badge
  await expect(page.getByTestId('active-date-badge')).toContainText('2026-03-01 to 2026-03-14'); // ref: filter-badge

  // Reload page
  await page.reload();

  // Assert filter persists
  await expect(page.getByTestId('active-date-badge')).toContainText('2026-03-01 to 2026-03-14');
});
