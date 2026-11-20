import { expect, test } from '@playwright/test';
import { PDFDocument } from 'pdf-lib';
import { randomBytes } from 'node:crypto';

test('HR reviews a PDF match and the submitting browser receives the reply', async ({ page, browser }) => {
  test.setTimeout(300_000);
  const topic = randomBytes(8).toString('hex');
  const question = `What does ${topic} cover?`;
  const passage = `The ${topic} benefit covers office equipment.`;
  const reply = 'HR confirms that this benefit covers office equipment.';
  const card = page.getByRole('article').filter({ has: page.getByRole('heading', { name: question, exact: true }) });

  await page.goto('/');
  await expect(page.getByRole('textbox', { name: 'Your question' })).toHaveCount(1);
  await expect(page.getByRole('checkbox', { name: 'Search HR policy documents' })).toBeChecked();
  await page.getByRole('textbox', { name: 'Your question' }).fill(question);
  await page.getByRole('button', { name: 'Ask question' }).click();
  await expect(card.getByText('Awaiting HR reply')).toBeVisible({ timeout: 90_000 });

  await page.getByRole('button', { name: 'HR view' }).click();
  await expect(card.getByText('No policy match')).toBeVisible();
  await page.getByLabel('Policy PDF', { exact: true }).setInputFiles({
    name: 'invalid.pdf', mimeType: 'application/pdf', buffer: Buffer.from('not a PDF')
  });
  await page.getByRole('button', { name: 'Upload PDF' }).click();
  await expect(page.getByRole('alert')).toContainText('Upload a valid, unencrypted PDF.');

  const pdf = await PDFDocument.create();
  pdf.addPage().drawText(passage, { x: 40, y: 700, size: 12 });
  const filename = `${topic}-policy.pdf`;
  await page.getByLabel('Policy PDF', { exact: true }).setInputFiles({
    name: filename, mimeType: 'application/pdf', buffer: Buffer.from(await pdf.save())
  });
  await page.getByRole('button', { name: 'Upload PDF' }).click();
  await expect(card.getByText('Policy match found after upload')).toBeVisible({ timeout: 120_000 });
  await expect(card.getByText('Awaiting HR reply')).toBeVisible();
  await card.getByText('Matching policy passages', { exact: false }).click();
  await expect(card.getByText(filename)).toBeVisible();
  await expect(card.getByText(passage)).toBeVisible();
  await card.getByRole('textbox', { name: 'HR reply' }).fill(reply);
  await card.getByRole('button', { name: 'Send reply and mark answered' }).click();
  await expect(card).toHaveCount(0);

  await page.getByLabel('Show', { exact: true }).selectOption('answered');
  await expect(card.getByText(reply)).toBeVisible();
  await page.getByRole('button', { name: 'Employee view' }).click();
  await page.reload();
  await expect(card.getByText(reply)).toBeVisible();

  const followUp = `${topic} benefit?`;
  await page.getByRole('textbox', { name: 'Your question' }).fill(followUp);
  await page.getByRole('button', { name: 'Ask question' }).click();
  const matchedCard = page.getByRole('article').filter({ has: page.getByRole('heading', { name: followUp, exact: true }) });
  await expect(matchedCard.getByText('Policy matched', { exact: true })).toBeVisible({ timeout: 90_000 });
  await expect(matchedCard.getByText('AI answer based on policy')).toBeVisible();

  const anotherBrowser = await browser.newContext();
  try {
    const anotherEmployee = await anotherBrowser.newPage();
    await anotherEmployee.goto(page.url());
    await expect(anotherEmployee.getByText('No questions submitted from this browser yet.')).toBeVisible();
  } finally {
    await anotherBrowser.close();
  }
});

test('general mode is labeled not checked and stays out of the review queue', async ({ page }) => {
  const question = `What is a typical leave policy? ${randomBytes(8).toString('hex')}`;
  const card = page.getByRole('article').filter({ has: page.getByRole('heading', { name: question, exact: true }) });
  await page.goto('/');
  await page.getByRole('checkbox', { name: 'Search HR policy documents' }).uncheck();
  await page.getByRole('textbox', { name: 'Your question' }).fill(question);
  await page.getByRole('button', { name: 'Ask question' }).click();
  await expect(card.getByText('General AI answer (not company policy)')).toBeVisible({ timeout: 90_000 });
  await page.getByRole('button', { name: 'HR view' }).click();
  await page.getByLabel('Show', { exact: true }).selectOption('not-checked');
  await expect(card).toBeVisible();
  await page.getByLabel('Show', { exact: true }).selectOption('review');
  await expect(card).toHaveCount(0);
});
