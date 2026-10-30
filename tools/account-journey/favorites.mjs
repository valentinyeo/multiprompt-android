import { readFile } from 'node:fs/promises';
import { setTimeout as delay } from 'node:timers/promises';

const [origin, token, mode, ...names] = process.argv.slice(2);
const features = 'favorite,muted,used,visits,input,settings,hosts,host_removal';
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-Layout-Features': features };
if (mode === 'read') {
    const [name, expectedText, baselinePath, retainedName] = names;
    if (names.length !== 4 || !name || !retainedName || !baselinePath || !['true', 'false'].includes(expectedText)) {
        throw new Error('read requires session, boolean, baseline and retained session');
    }
    const baseline = JSON.parse(await readFile(baselinePath, 'utf8'));
    if (!Array.isArray(baseline.favorites)) throw new Error('invalid favorite baseline');
    const baselineId = session => {
        const matches = baseline.favorites.filter(value => typeof value === 'string' && value.endsWith(`:${session.toLowerCase()}`));
        if (matches.length !== 1 || !/^\d+:/.test(matches[0])) throw new Error(`invalid baseline identity for ${session}`);
        return matches[0];
    };
    const targetId = baselineId(name);
    const retainedId = baselineId(retainedName);
    if (targetId === retainedId) throw new Error('favorite identities must differ');
    const expected = expectedText === 'true';
    const deadline = performance.now() + 20_000;
    while (performance.now() < deadline) {
        const remaining = Math.ceil(deadline - performance.now());
        const response = await fetch(`${origin}/v1/layout`, { headers, signal: AbortSignal.timeout(Math.min(5_000, remaining)) });
        if (response.status !== 200) throw new Error(`layout read ${response.status}`);
        const { revision, layout } = await response.json();
        if (!Number.isSafeInteger(revision) || revision < 0 || !layout || !Array.isArray(layout.tabs)) {
            throw new Error('invalid layout response');
        }
        const findTab = (session, identity) => {
            const matches = layout.tabs.filter(tab => tab && typeof tab.tmux_session === 'string' && tab.tmux_session.toLowerCase() === session.toLowerCase());
            if (matches.length !== 1 || matches[0].kind !== 'tmux' || !Number.isInteger(matches[0].tmux_host) || `${matches[0].tmux_host}:${matches[0].tmux_session.toLowerCase()}` !== identity || typeof matches[0].workspace !== 'string' || !matches[0].workspace || typeof matches[0].favorite !== 'boolean') {
                throw new Error(`invalid retained tab identity or favorite for ${session}`);
            }
            return matches[0];
        };
        const tab = findTab(name, targetId);
        const retained = findTab(retainedName, retainedId);
        if (!retained.favorite) throw new Error(`retained favorite changed for ${retainedName}`);
        if (tab.favorite === expected) {
            console.log(JSON.stringify({ revision, session: tab.tmux_session, favorite: tab.favorite, workspace: tab.workspace }));
            process.exit(0);
        }
        const wait = deadline - performance.now();
        if (wait > 0) await delay(Math.min(1_000, wait));
    }
    throw new Error(`timed out waiting for favorite state for ${name}`);
}
const read = await fetch(`${origin}/v1/layout`, { headers });
if (read.status !== 200) throw new Error(`layout read ${read.status}`);
const { revision, layout } = await read.json();
const id = tab => `${tab.tmux_host}:${tab.tmux_session.toLowerCase()}`;
if (mode === 'star') {
    for (const name of names) {
        const tab = layout.tabs.find(t => t.tmux_session === name);
        if (tab) tab.favorite = true;
        else layout.tabs.push({ kind: 'tmux', name, tmux_session: name, tmux_host: 0, workspace: 'Work', favorite: true });
    }
} else if (mode === 'unstar') {
    for (const name of names) {
        const tab = layout.tabs.find(t => t.tmux_session === name);
        if (!tab) throw new Error(`no tab ${name}`);
        tab.favorite = false;
    }
} else throw new Error('mode star, unstar or read');
const write = await fetch(`${origin}/v1/layout`, { method: 'PUT', headers, body: JSON.stringify({ base_revision: revision, layout }) });
if (write.status !== 200) throw new Error(`layout write ${write.status} ${await write.text()}`);
const stored = await (await fetch(`${origin}/v1/layout`, { headers })).json();
console.log(JSON.stringify({ revision: stored.revision, favorites: stored.layout.tabs.filter(t => t.favorite === true).map(id) }));
