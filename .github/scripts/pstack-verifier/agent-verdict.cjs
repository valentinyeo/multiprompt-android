'use strict';

const VERDICTS = ['PASS', 'PASS+NOTES', 'FAIL'];

function parseJson(text) {
  const source = String(text ?? '').trim();
  const fenced = source.match(/```(?:json)?\s*([\s\S]*?)\s*```/i);
  const open = source.indexOf('{');
  const close = source.lastIndexOf('}');
  const candidates = [source, fenced?.[1], open >= 0 && close > open ? source.slice(open, close + 1) : null];
  for (const candidate of candidates) {
    if (!candidate) continue;
    try {
      const value = JSON.parse(candidate);
      if (value && typeof value === 'object' && !Array.isArray(value)) return value;
    } catch {
      continue;
    }
  }
  return null;
}

function oneLine(value, limit = 600) {
  return String(value ?? '').replace(/\s+/g, ' ').trim().slice(0, limit);
}

function parseAgentVerdict(content) {
  const parsed = parseJson(content);
  if (!parsed) throw new Error('agent review answer is not JSON');
  if (!VERDICTS.includes(parsed.verdict)) throw new Error('agent review answer has no valid verdict');
  if (typeof parsed.summary !== 'string' || !parsed.summary.trim()) throw new Error('agent review answer has no summary');
  const evidence = Array.isArray(parsed.evidence) ? parsed.evidence : [];
  const normalized = [];
  for (const item of evidence) {
    if (!item || typeof item !== 'object' || Array.isArray(item)) throw new Error('agent review evidence entry is invalid');
    if (typeof item.file !== 'string' || !item.file.trim()) throw new Error('agent review evidence entry has no file');
    if (typeof item.what !== 'string' || !item.what.trim()) throw new Error('agent review evidence entry has no what');
    normalized.push({ file: item.file.trim(), what: oneLine(item.what, 400) });
  }
  return { verdict: parsed.verdict, summary: oneLine(parsed.summary), evidence: normalized };
}

module.exports = { VERDICTS, parseJson, parseAgentVerdict };