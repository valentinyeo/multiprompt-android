import fs from 'node:fs';
import { parseTicketId, canonicalTicketId, multipromptMovedBoardId } from './ticket-id.js';

function acceptsTaskForId(id, ticket, task) {
  if (!task?.title) return false;
  const moved = ticket.boardId === 2060 && task.boardId === multipromptMovedBoardId;
  if (task.boardId !== ticket.boardId && !moved) return false;
  if (moved) return true;
  if (task.ticketNumber == null && task.uniqueIndex == null) return false;
  if (task.ticketNumber != null && parseTicketId(task.ticketNumber)?.id !== id) return false;
  if (task.uniqueIndex != null && String(task.uniqueIndex) !== ticket.number) return false;
  return true;
}

export function ticketRecord(id, title) {
  id = canonicalTicketId(id);
  if (typeof title !== 'string' || title !== title.trim() || !title || Buffer.from(title).toString('utf8') !== title || Buffer.byteLength(`${id} ${title}`) > 512 || /[\x00-\x1f\x7f]/.test(title) || /checks and fixes, no visible change/i.test(title) || /(?:https?:\/\/|[A-Za-z]:\\|(?:^|\s)\/(?:home|tmp|var|etc)\/)/i.test(title)) {
    throw new Error(`Missing or invalid title for ${id}`);
  }
  return { id, title };
}

export const ticketTitlesDir = new URL('./ticket-titles/', import.meta.url);

export function ticketTitleFile(id) {
  id = canonicalTicketId(id);
  return `${id}.txt`;
}

export function loadTicketTitles(dir = ticketTitlesDir) {
  const titles = {};
  for (const name of fs.readdirSync(dir).sort()) {
    const ticket = name.endsWith('.txt') && parseTicketId(name.slice(0, -4));
    if (!ticket) throw new Error(`Unexpected entry in ticket title catalog: ${name}`);
    const title = fs.readFileSync(new URL(name, dir), 'utf8').replace(/\r?\n$/, '');
    const record = ticketRecord(ticket.id, title);
    if (Object.hasOwn(titles, record.id) && titles[record.id] !== record.title) throw new Error(`Conflicting titles for ${record.id}`);
    titles[record.id] = record.title;
  }
  return titles;
}

export function ticketLine(record) {
  const { id, title } = ticketRecord(record.id, record.title);
  return `${id} ${title}`;
}

function catalogTitles(catalog) {
  const titles = {};
  for (const [id, title] of Object.entries(catalog)) {
    const canonical = canonicalTicketId(id);
    if (Object.hasOwn(titles, canonical) && titles[canonical] !== title) throw new Error(`Conflicting titles for ${canonical}`);
    titles[canonical] = title;
  }
  return titles;
}

function skipError(kind, message) {
  const error = new Error(message);
  error.kind = kind;
  return error;
}

async function fetchTicketTitle(id, token, request, remaining) {
  const ticket = parseTicketId(id);
  if (!token) throw skipError('no-token', `Missing title for ${id}; provide a catalog or release token`);
  let response;
  try {
    response = await request(`https://api.hypertask.ai/api/mcp/tasks?ticket_number=${id}`, {
      headers: { Authorization: `Bearer ${token}` },
      signal: AbortSignal.timeout(Math.min(15000, remaining)),
      redirect: 'error',
    });
  } catch {
    throw skipError('lookup-failed', `Title lookup failed for ${id}`);
  }
  if (!response.ok) throw skipError('lookup-failed', `Title lookup failed for ${id}`);
  const data = await response.json().catch(() => null);
  const task = data?.tasks?.[0];
  if (data?.success !== true || !Array.isArray(data.tasks) || data.tasks.length !== 1 || !acceptsTaskForId(id, ticket, task)) throw skipError('not-found', `Unresolved title for ${id}`);
  return ticketRecord(id, task.title).title;
}

export async function resolveTicketTitles(ids, catalog, token, request = fetch) {
  const titles = catalogTitles(catalog);
  ids = ids.map(canonicalTicketId);
  const missing = [...new Set(ids)].filter(id => !Object.hasOwn(titles, id));
  if (missing.length > 2000) throw new Error('Too many unresolved ticket titles');
  const deadline = Date.now() + 120000;
  for (const id of missing) {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('Ticket title lookup timed out');
    titles[id] = await fetchTicketTitle(id, token, request, remaining);
  }
  for (const id of ids) ticketRecord(id, titles[id]);
  return titles;
}

export async function resolveReleaseTitles(ids, catalog, token, request = fetch) {
  const titles = catalogTitles(catalog);
  const unresolved = [];
  const deadline = Date.now() + 120000;
  for (const id of [...new Set(ids.map(value => parseTicketId(value)?.id).filter(Boolean))]) {
    if (Object.hasOwn(titles, id)) continue;
    try {
      const remaining = deadline - Date.now();
      if (remaining <= 0) throw skipError('timed-out', 'Ticket title lookup timed out');
      titles[id] = await fetchTicketTitle(id, token, request, remaining);
    } catch (error) {
      unresolved.push([id, error.kind || 'lookup-failed']);
    }
  }
  return { titles, unresolved };
}
