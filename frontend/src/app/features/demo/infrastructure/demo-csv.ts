/** Neutralize spreadsheet formulas even behind whitespace and control prefixes. */
export function demoCsv(rows: readonly (readonly unknown[])[]): string {
  return rows.map(row => row.map(value => {
    let text = String(value ?? '');
    if (/^[\s\u0000-\u001f]*[=+@-]/.test(text) || /^[\t\r\n]/.test(text)) text = "'" + text;
    return '"' + text.replace(/"/g, '""') + '"';
  }).join(';')).join('\r\n');
}
