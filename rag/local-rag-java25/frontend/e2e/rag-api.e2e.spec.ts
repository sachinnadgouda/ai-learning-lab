import { APIRequestContext, APIResponse, expect, test } from '@playwright/test';
import { PDFDocument, StandardFonts } from 'pdf-lib';

async function createOfficePolicyPdf(): Promise<Buffer> {
  const pdf = await PDFDocument.create();
  const page = pdf.addPage([595, 842]);
  const font = await pdf.embedFont(StandardFonts.Helvetica);
  const text = [
    'Office HR Policy Summary',
    'Annual leave: Employees receive 25 days of annual leave each calendar year.',
    'Work from home policy: Employees may work from home up to 2 days per week.',
    'Work from abroad policy: Employees may work from abroad for up to 20 business days per year with manager approval.',
    'Sick leave: Employees receive 12 paid sick leave days each calendar year.',
    'Maternity leave: Eligible employees receive 26 weeks of maternity leave.',
    'This policy summary is for internal reference.'
  ];
  let y = 790;
  for (const line of text) {
    page.drawText(line, { x: 40, y, size: 12, font });
    y -= 22;
  }
  return Buffer.from(await pdf.save());
}

async function postWithRetry(
  request: APIRequestContext,
  url: string,
  options: Parameters<APIRequestContext['post']>[1]
): Promise<APIResponse> {
  const timeoutMs = 180_000;
  const started = Date.now();
  let attempt = 0;
  let lastResponse: APIResponse | null = null;
  let lastBody = '';

  while (Date.now() - started < timeoutMs) {
    attempt += 1;
    const response = await request.post(url, options);
    const status = response.status();
    if (![502, 503, 504].includes(status)) return response;

    lastResponse = response;
    lastBody = await response.text();
    await new Promise(resolve => setTimeout(resolve, Math.min(6_000, attempt * 1_000)));
  }

  if (lastResponse) {
    expect(
      lastResponse.status(),
      `Timed out waiting for ${url} to be ready. Last status=${lastResponse.status()}, body=${lastBody}`
    ).not.toBeGreaterThanOrEqual(500);
    return lastResponse;
  }

  throw new Error(`No response received for ${url}`);
}

test('RAG and non-RAG API responses for HR policy questions', async ({ request }) => {
  test.setTimeout(600_000);
  const pdfBytes = await createOfficePolicyPdf();

  const ingestResponse = await postWithRetry(request, '/api/v1/documents/pdf', {
    multipart: {
      file: {
        name: 'office-policy.pdf',
        mimeType: 'application/pdf',
        buffer: pdfBytes
      }
    }
  });
  expect(ingestResponse.status()).toBe(201);
  const ingestBody = await ingestResponse.json();
  expect(ingestBody.chunks).toBeGreaterThan(0);

  const supportedQuestions = [
    'How many annual leave days do employees get?',
    'What is the work from home policy?',
    'How many days can employees work from abroad?',
    'How many sick leave days are granted?',
    'What is the maternity leave entitlement?'
  ];

  for (const question of supportedQuestions) {
    const response = await postWithRetry(request, '/api/v1/questions', { data: { question } });
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(body.grounded).toBeTruthy();
    expect(body.source).toBe('rag');
    expect(body.sources.length).toBeGreaterThan(0);
    expect(String(body.answer).trim().length).toBeGreaterThan(0);
  }

  const unsupported = await postWithRetry(request, '/api/v1/questions', {
    data: { question: 'Does the home office policy include instrument subsidy reimbursement?' }
  });
  expect(unsupported.ok()).toBeTruthy();
  const unsupportedBody = await unsupported.json();
  expect(unsupportedBody.grounded).toBeFalsy();
  expect(unsupportedBody.source).toBe('internet');
  expect(String(unsupportedBody.advisory)).toContain('manager or HR');

  const general = await postWithRetry(request, '/api/v1/questions/general', {
    data: { question: 'What is a typical annual leave policy?' }
  });
  expect(general.ok()).toBeTruthy();
  const generalBody = await general.json();
  expect(generalBody.grounded).toBeFalsy();
  expect(generalBody.source).toBe('internet');
  expect(String(generalBody.advisory)).toContain('without RAG context');
  expect(String(generalBody.answer).trim().length).toBeGreaterThan(0);
});
