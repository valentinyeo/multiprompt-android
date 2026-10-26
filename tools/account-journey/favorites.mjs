const [origin, token, mode, ...names] = process.argv.slice(2);
const features = 'favorite,muted,used,visits,input,settings,hosts,host_removal';
const headers = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', 'X-Layout-Features': features };
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
} else throw new Error('mode star or unstar');
const write = await fetch(`${origin}/v1/layout`, { method: 'PUT', headers, body: JSON.stringify({ base_revision: revision, layout }) });
if (write.status !== 200) throw new Error(`layout write ${write.status} ${await write.text()}`);
const stored = await (await fetch(`${origin}/v1/layout`, { headers })).json();
console.log(JSON.stringify({ revision: stored.revision, favorites: stored.layout.tabs.filter(t => t.favorite === true).map(id) }));
