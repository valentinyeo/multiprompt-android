import { tokenize } from './strip-comments.mjs';

export function nativeTestParts(source) {
  const { tokens, comments } = tokenize(source, 'zig');
  const positions = [];
  let cursor = 0, comment = 0;
  for (const [, value] of tokens) {
    while (cursor < source.length) {
      if (comments[comment]?.start === cursor) cursor = comments[comment++].end;
      else if (/\s/.test(source[cursor])) cursor++;
      else break;
    }
    if (!source.startsWith(value, cursor)) throw new Error('Ambiguous Zig token');
    positions.push(cursor);
    cursor += value.length;
  }
  const names = [], pieces = [];
  let depth = 0, last = 0;
  for (let i = 0; i < tokens.length; i++) {
    const [kind, value] = tokens[i];
    if (depth === 0 && kind === 'word' && value === 'test') {
      const start = positions[i];
      if (tokens[i + 1]?.[0] !== 'string' || tokens[i + 2]?.[1] !== '{') throw new Error('Ambiguous Zig test');
      const name = tokens[i + 1][1];
      if (names.includes(name)) throw new Error('Duplicate Zig test');
      names.push(name);
      i += 2;
      let testDepth = 1;
      while (testDepth && ++i < tokens.length) {
        if (tokens[i][0] === 'code' && tokens[i][1] === '{') testDepth++;
        if (tokens[i][0] === 'code' && tokens[i][1] === '}') testDepth--;
      }
      if (testDepth) throw new Error('Unclosed Zig test');
      pieces.push(source.slice(last, start));
      last = positions[i] + tokens[i][1].length;
    } else if (kind === 'code' && value === '{') depth++;
    else if (kind === 'code' && value === '}') {
      if (--depth < 0) throw new Error('Unbalanced Zig source');
    }
  }
  if (depth) throw new Error('Unbalanced Zig source');
  pieces.push(source.slice(last));
  return { names, outside: pieces.join('') };
}
