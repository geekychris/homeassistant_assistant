import { useEffect, useState } from 'react';
import { api } from './api';
import type { Instance } from './types';

/**
 * Manage Home Assistant instances. The token field is write-only — the backend
 * never sends it back, so leaving it blank during edit keeps the existing one.
 */
export default function Settings() {
  const [list, setList] = useState<Instance[]>([]);
  const [editing, setEditing] = useState<Partial<Instance> & { token?: string } | null>(null);

  async function reload() {
    setList(await api.instances());
  }
  useEffect(() => { reload(); }, []);

  function startNew() {
    setEditing({ name: '', internalUrl: '', externalUrl: '', token: '', enabled: true, isDefault: false });
  }
  function startEdit(i: Instance) {
    setEditing({ ...i, token: '' });
  }
  async function save() {
    if (!editing || !editing.name?.trim()) return;
    if (editing.id) await api.updateInstance(editing.id, editing);
    else await api.createInstance(editing);
    setEditing(null);
    reload();
  }
  async function del(i: Instance) {
    if (!confirm(`Delete instance "${i.name}"? Cached entities will go with it.`)) return;
    await api.deleteInstance(i.id);
    reload();
  }
  async function setDefault(i: Instance) {
    await api.updateInstance(i.id, { isDefault: true });
    reload();
  }
  async function toggleEnabled(i: Instance) {
    await api.updateInstance(i.id, { enabled: !i.enabled });
    reload();
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 12 }}>
        <h2 style={{ margin: 0 }}>Home Assistant instances</h2>
        <button onClick={startNew}>+ Add instance</button>
      </div>
      <p style={{ color: 'var(--muted)', marginTop: 0 }}>
        Each instance has an optional <strong>internal URL</strong> (preferred when reachable) and
        <strong> external URL</strong> (fallback when away from home). The token is a long-lived
        access token from HA → user profile → Security.
      </p>

      <ul style={{ listStyle: 'none', padding: 0 }}>
        {list.map((i) => (
          <li key={i.id}
              style={{
                border: '1px solid var(--border)', borderRadius: 6, padding: 10,
                marginBottom: 8, opacity: i.enabled ? 1 : 0.5,
              }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}>
              <div>
                <strong style={{ fontSize: 16 }}>{i.name}</strong>
                {i.isDefault && <span style={{ marginLeft: 8, color: '#2a7' }}>★ default</span>}
                <span style={{ marginLeft: 8, color: statusColor(i.status) }}>{i.status}</span>
              </div>
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
                <button onClick={() => startEdit(i)}>Edit</button>
                {!i.isDefault && <button onClick={() => setDefault(i)}>Make default</button>}
                <button onClick={() => toggleEnabled(i)}>{i.enabled ? 'Disable' : 'Enable'}</button>
                <button onClick={() => del(i)}>Delete</button>
              </div>
            </div>
            <div style={{ color: 'var(--muted)', fontSize: 13, marginTop: 4 }}>
              <div>internal: {i.internalUrl ?? <em>—</em>}</div>
              <div>external: {i.externalUrl ?? <em>—</em>}</div>
              <div>token: {i.tokenSet ? '✓ set' : <em style={{ color: 'crimson' }}>missing</em>}</div>
            </div>
          </li>
        ))}
      </ul>

      {editing && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.4)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
        }}>
          <div style={{ background: 'var(--bg)', border: '1px solid var(--border)', borderRadius: 8,
                        padding: 16, width: 'min(480px, 100%)' }}>
            <h3 style={{ marginTop: 0 }}>{editing.id ? `Edit "${editing.name}"` : 'Add instance'}</h3>
            <Field label="Name (e.g. belmont)">
              <input value={editing.name ?? ''} onChange={(e) => setEditing({ ...editing, name: e.target.value })} />
            </Field>
            <Field label="Internal URL (preferred when at home)">
              <input value={editing.internalUrl ?? ''} placeholder="http://homeassistant.local:8123/"
                     onChange={(e) => setEditing({ ...editing, internalUrl: e.target.value })} />
            </Field>
            <Field label="External URL (fallback when away)">
              <input value={editing.externalUrl ?? ''} placeholder="https://abc123.ui.nabu.casa/"
                     onChange={(e) => setEditing({ ...editing, externalUrl: e.target.value })} />
            </Field>
            <Field label={`Long-lived access token${editing.id ? ' (leave blank to keep existing)' : ''}`}>
              <input type="password" value={editing.token ?? ''}
                     onChange={(e) => setEditing({ ...editing, token: e.target.value })} />
            </Field>
            <label style={{ display: 'block', marginTop: 8 }}>
              <input type="checkbox" checked={editing.isDefault ?? false}
                     onChange={(e) => setEditing({ ...editing, isDefault: e.target.checked })} />
              {' '}Default instance
            </label>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'flex-end', marginTop: 12 }}>
              <button onClick={() => setEditing(null)}>Cancel</button>
              <button onClick={save} disabled={!editing.name?.trim()}>Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'block', marginTop: 8 }}>
      <div style={{ fontSize: 12, color: 'var(--muted)', marginBottom: 2 }}>{label}</div>
      <div>{children}</div>
      <style>{`label > div > input { width: 100%; padding: 6px; }`}</style>
    </label>
  );
}

function statusColor(s: string): string {
  if (s === 'connected') return '#2a7';
  if (s === 'connecting') return '#888';
  return '#c33';
}
