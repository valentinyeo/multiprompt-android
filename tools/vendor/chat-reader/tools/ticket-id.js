const prefixes = { IGSH: ['IGSH', 2060], SOFT: ['IGSH', 2060], HYCI: ['HYCI', 11514], MUBO: ['MUBO', 13734] };
const multipromptMovedBoardId = 13734;
const unlinkedTicketId = 'no-ticket';

function parseTicketId(value) {
  const match = typeof value === 'string' && /^([A-Z][A-Z0-9]*)-([1-9]\d*)$/.exec(value);
  const board = match && match[0] === value && Object.hasOwn(prefixes, match[1]) && prefixes[match[1]];
  return board ? { id: `${board[0]}-${match[2]}`, prefix: board[0], number: match[2], boardId: board[1] } : null;
}

function canonicalTicketId(value) {
  const ticket = parseTicketId(value);
  if (!ticket) throw new Error(`Invalid ticket ID: ${value}`);
  return ticket.id;
}

function ticketKeyKind(value) {
  return typeof value === 'string' && /^[A-Z][A-Z0-9]*-[1-9]\d*$/.test(value) ? 'unknown-prefix' : 'malformed-key';
}

function fragmentTicketId(file) {
  const match = /^changelog\.d\/([A-Z][A-Z0-9]*-\d+)-[^/]+\.md$/.exec(file);
  if (!match) throw new Error(`Bad fragment filename: ${file}`);
  return canonicalTicketId(match[1]);
}

function ticketIds(text) {
  return [...new Set([...text.matchAll(/(?:^|[^A-Za-z0-9-])([A-Z][A-Z0-9]*-\d+)(?![A-Za-z0-9-])/g)].map(match => parseTicketId(match[1])?.id).filter(Boolean))];
}

module.exports = { parseTicketId, canonicalTicketId, fragmentTicketId, ticketIds, ticketKeyKind, multipromptMovedBoardId, unlinkedTicketId };
