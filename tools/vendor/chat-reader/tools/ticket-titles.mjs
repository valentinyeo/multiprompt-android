const boards = { IGSH: 2060, HYCI: 11514 };
const aliases = { IGSH: 'SOFT' };

export function ticketRecord(id, title) {
  if (typeof id !== 'string' || !/^(?:IGSH|HYCI)-[1-9]\d*$/.test(id)) throw new Error(`Invalid ticket ID: ${id}`);
  if (typeof title !== 'string' || title !== title.trim() || !title || Buffer.from(title).toString('utf8') !== title || Buffer.byteLength(`${id} ${title}`) > 512 || /[\x00-\x1f\x7f]/.test(title) || /checks and fixes, no visible change/i.test(title) || /(?:https?:\/\/|[A-Za-z]:\\|(?:^|\s)\/(?:home|tmp|var|etc)\/)/i.test(title)) {
    throw new Error(`Missing or invalid title for ${id}`);
  }
  return { id, title };
}

export function ticketLine(record) {
  const { id, title } = ticketRecord(record.id, record.title);
  return `${id} ${title}`;
}

export async function resolveTicketTitles(ids, catalog, token, request = fetch) {
  const titles = { ...catalog };
  const missing = [...new Set(ids)].filter(id => !Object.hasOwn(titles, id));
  if (missing.length > 2000) throw new Error('Too many unresolved ticket titles');
  const deadline = Date.now() + 120000;
  for (const id of missing) {
    const match = /^(IGSH|HYCI)-([1-9]\d*)$/.exec(id);
    if (!match) throw new Error(`Invalid ticket ID: ${id}`);
    if (!token) throw new Error(`Missing title for ${id}; provide a catalog or release token`);
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new Error('Ticket title lookup timed out');
    let response;
    try {
      response = await request(`https://api.hypertask.ai/api/mcp/tasks?ticket_number=${id}`, {
        headers: { Authorization: `Bearer ${token}` },
        signal: AbortSignal.timeout(Math.min(15000, remaining)),
        redirect: 'error',
      });
    } catch {
      throw new Error(`Title lookup failed for ${id}`);
    }
    if (!response.ok) throw new Error(`Title lookup failed for ${id}`);
    const data = await response.json().catch(() => null);
    const task = data?.tasks?.[0];
    if (data?.success !== true || !Array.isArray(data.tasks) || data.tasks.length !== 1 || task?.boardId !== boards[match[1]] || (task.ticketNumber == null && task.uniqueIndex == null) || (task.ticketNumber != null && task.ticketNumber !== id && task.ticketNumber !== `${aliases[match[1]] ?? ''}-${match[2]}`) || (task.uniqueIndex != null && String(task.uniqueIndex) !== match[2])) throw new Error(`Unresolved title for ${id}`);
    titles[id] = ticketRecord(id, task.title).title;
  }
  for (const id of ids) ticketRecord(id, titles[id]);
  return titles;
}
