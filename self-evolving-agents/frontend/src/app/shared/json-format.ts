export interface FormattedJson {
  readonly text: string;
  readonly isJson: boolean;
}

/** Pretty-prints JSON when possible; otherwise returns the text unchanged (exact payloads are never altered in storage). */
export function formatJson(raw: string | null | undefined): FormattedJson {
  if (raw === null || raw === undefined) return { text: '', isJson: false };
  try {
    return { text: JSON.stringify(JSON.parse(raw), null, 2), isJson: true };
  } catch {
    return { text: raw, isJson: false };
  }
}

export function escapeHtml(value: string): string {
  return value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

const TOKEN = /("(?:\\u[a-fA-F0-9]{4}|\\[^u]|[^\\"])*"(?:\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g;

/** Returns HTML (already escaped) with span-based token classes. */
export function highlightJson(text: string): string {
  return escapeHtml(text).replace(TOKEN, (match) => {
    let cls = 'tok-number';
    if (match.startsWith('&quot;') || match.startsWith('"')) cls = match.endsWith(':') ? 'tok-key' : 'tok-string';
    else if (match === 'true' || match === 'false') cls = 'tok-bool';
    else if (match === 'null') cls = 'tok-null';
    return `<span class="${cls}">${match}</span>`;
  });
}
