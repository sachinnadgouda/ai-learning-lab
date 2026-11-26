import { escapeHtml, formatJson, highlightJson } from './json-format';

describe('json-format', () => {
  it('pretty-prints valid JSON', () => {
    expect(formatJson('{"a":1}')).toEqual({ text: '{\n  "a": 1\n}', isJson: true });
  });
  it('returns non-JSON text unchanged', () => {
    expect(formatJson('plain text')).toEqual({ text: 'plain text', isJson: false });
  });
  it('highlights keys, strings, numbers, booleans and null', () => {
    const html = highlightJson(formatJson('{"k":"v","n":1,"b":true,"z":null}').text);
    expect(html).toContain('<span class="tok-key">"k":</span>');
    expect(html).toContain('<span class="tok-string">"v"</span>');
    expect(html).toContain('<span class="tok-number">1</span>');
    expect(html).toContain('<span class="tok-bool">true</span>');
    expect(html).toContain('<span class="tok-null">null</span>');
  });
  it('escapes HTML so recorded prompts can never inject markup', () => {
    expect(highlightJson('{"x":"<img src=x onerror=alert(1)>"}')).not.toContain('<img');
    expect(escapeHtml('<b>&')).toBe('&lt;b&gt;&amp;');
  });
});
