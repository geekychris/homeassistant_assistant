import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { Area, EntityNoteRow, HaEntity } from './types';

/**
 * Areas tab. Each entity is a card with an inline control matched to its
 * EFFECTIVE category (entity_note.category if set, else the HA domain).
 * Click a card to edit label / category / tags / note.
 *
 * Group selector at the top: by area (default) or by category — the latter
 * is the "which switches are really lights?" view, since reclassified
 * switches show up in the 'light' group.
 */
export default function Catalog({ areas, entities }: { areas: Area[]; entities: HaEntity[] }) {
  const [notes, setNotes] = useState<Record<string, EntityNoteRow>>({});
  const [groupBy, setGroupBy] = useState<'area' | 'category'>('area');
  const [filter, setFilter] = useState<string>('');
  const [editing, setEditing] = useState<string | null>(null);

  async function reloadNotes() {
    const list = await api.notes();
    const map: Record<string, EntityNoteRow> = {};
    list.forEach((n) => { map[n.entityId] = n; });
    setNotes(map);
  }
  useEffect(() => { reloadNotes(); }, []);

  // Build augmented entity list with effective category.
  const augmented = useMemo(() =>
    entities.map((e) => {
      const n = notes[e.entityId];
      const category = (n?.category ?? e.domain).toLowerCase();
      return { e, n, category };
    }),
  [entities, notes]);

  // Available category labels for the filter dropdown.
  const categoryOptions = useMemo(() => {
    const s = new Set<string>();
    augmented.forEach((a) => s.add(a.category));
    return [...s].sort();
  }, [augmented]);

  const visible = augmented.filter((a) =>
    !filter ||
    a.category === filter ||
    (a.e.friendlyName ?? a.e.entityId).toLowerCase().includes(filter.toLowerCase()),
  );

  // Group.
  const groups = new Map<string, typeof visible>();
  for (const a of visible) {
    const key = groupBy === 'area'
      ? (a.e.areaId ?? '__unassigned__')
      : a.category;
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key)!.push(a);
  }
  const areaName = (id: string) =>
    id === '__unassigned__' ? 'Unassigned'
      : areas.find((a) => a.id === id)?.name ?? id;
  const groupLabel = (id: string) => groupBy === 'area' ? areaName(id) : capitalise(id);

  const sortedKeys = [...groups.keys()].sort((a, b) => groupLabel(a).localeCompare(groupLabel(b)));

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap' }}>
        <span style={{ color: 'var(--muted)' }}>{entities.length} entities</span>
        <span style={{ color: 'var(--muted)' }}>·</span>
        <span>group by:</span>
        {(['area', 'category'] as const).map((g) => (
          <button key={g} onClick={() => setGroupBy(g)}
                  style={{ padding: '4px 8px', background: groupBy === g ? '#333' : 'var(--tile)',
                           color: groupBy === g ? '#fff' : 'inherit',
                           border: '1px solid var(--border)', borderRadius: 4 }}>
            {g}
          </button>
        ))}
        <span style={{ marginLeft: 12 }}>filter:</span>
        <select value={filter} onChange={(e) => setFilter(e.target.value)}
                style={{ padding: 4, background: 'var(--tile)', border: '1px solid var(--border)', borderRadius: 4 }}>
          <option value="">all</option>
          {categoryOptions.map((c) => <option key={c} value={c}>{c}</option>)}
        </select>
      </div>

      {sortedKeys.map((key) => {
        const items = groups.get(key)!.slice().sort((a, b) =>
          (a.e.friendlyName ?? a.e.entityId).localeCompare(b.e.friendlyName ?? b.e.entityId));
        return (
          <section key={key} style={{ marginBottom: 20 }}>
            <h2 style={{ borderBottom: '1px solid var(--border)', paddingBottom: 4, margin: '0 0 8px', fontSize: 16 }}>
              {groupLabel(key)} <small style={{ color: 'var(--muted)' }}>({items.length})</small>
            </h2>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(260px, 1fr))', gap: 8 }}>
              {items.map(({ e, n, category }) => (
                <EntityCard
                  key={e.entityId}
                  entity={e}
                  note={n}
                  category={category}
                  editing={editing === e.entityId}
                  onToggleEdit={() => setEditing(editing === e.entityId ? null : e.entityId)}
                  onChange={reloadNotes}
                />
              ))}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function EntityCard({
  entity, note, category, editing, onToggleEdit, onChange,
}: {
  entity: HaEntity;
  note: EntityNoteRow | undefined;
  category: string;
  editing: boolean;
  onToggleEdit: () => void;
  onChange: () => void;
}) {
  const headline = note?.label || entity.friendlyName || entity.entityId;
  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 6, padding: 8, background: 'var(--tile)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 6, alignItems: 'baseline' }}>
        <div style={{ minWidth: 0 }}>
          <div style={{ fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {headline}
          </div>
          <div style={{ fontSize: 11, color: 'var(--muted)' }}>
            {category}{entity.deviceClass ? ` · ${entity.deviceClass}` : ''}
          </div>
        </div>
        <button onClick={onToggleEdit} style={{ background: 'transparent', border: 'none', color: 'var(--muted)', cursor: 'pointer' }}>
          {editing ? '×' : '⚙'}
        </button>
      </div>
      <div style={{ marginTop: 6 }}>
        <Control entity={entity} category={category} />
      </div>
      {note?.tags && note.tags.length > 0 && (
        <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 4 }}>
          {note.tags.map((t) => (
            <span key={t} style={{ fontSize: 11, padding: '1px 6px', border: '1px solid var(--border)', borderRadius: 10, color: 'var(--muted)' }}>{t}</span>
          ))}
        </div>
      )}
      {editing && <EditPanel entity={entity} note={note} onClose={onToggleEdit} onSaved={onChange} />}
    </div>
  );
}

function Control({ entity, category }: { entity: HaEntity; category: string }) {
  const [busy, setBusy] = useState(false);
  const state = entity.state ?? '';
  const isOn = state === 'on' || state === 'home' || state === 'open';
  const isReadOnly = ['sensor', 'binary_sensor', 'temperature', 'humidity', 'battery', 'motion'].includes(category);

  const brightnessAttr = parseAttr(entity.attributes, 'brightness');
  const supportsBrightness = entity.domain === 'light' && brightnessAttr != null;

  async function act(action: 'on' | 'off' | 'toggle' | 'set', params?: Record<string, unknown>) {
    setBusy(true);
    try { await api.control(entity.entityId, action, params); }
    catch (e) { alert(`control failed: ${e}`); }
    finally { setBusy(false); }
  }

  if (isReadOnly) {
    return (
      <div style={{ fontSize: 14 }}>
        <span style={{ fontWeight: 600 }}>{state || '—'}</span>
        {entity.unit && <span style={{ color: 'var(--muted)' }}> {entity.unit}</span>}
      </div>
    );
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
        <button
          onClick={() => act(isOn ? 'off' : 'on')}
          disabled={busy || state === 'unavailable'}
          style={{
            padding: '4px 14px', borderRadius: 14, border: '1px solid var(--border)',
            background: isOn ? '#2a7' : 'var(--tile)', color: isOn ? '#fff' : 'inherit',
            minWidth: 60,
          }}>
          {isOn ? 'ON' : 'OFF'}
        </button>
        <span style={{ fontSize: 12, color: state === 'unavailable' ? '#c33' : 'var(--muted)' }}>{state || '—'}</span>
      </div>
      {supportsBrightness && (
        <div style={{ marginTop: 6, display: 'flex', gap: 6, alignItems: 'center' }}>
          <input
            type="range"
            min={1}
            max={255}
            defaultValue={Number(brightnessAttr) || 128}
            disabled={busy || !isOn}
            onMouseUp={(e) => act('set', { brightness: Number((e.target as HTMLInputElement).value) })}
            onTouchEnd={(e) => act('set', { brightness: Number((e.target as HTMLInputElement).value) })}
            style={{ flex: 1 }}
          />
          <span style={{ fontSize: 11, color: 'var(--muted)', width: 32, textAlign: 'right' }}>
            {Math.round((Number(brightnessAttr) || 0) / 255 * 100)}%
          </span>
        </div>
      )}
    </div>
  );
}

function EditPanel({
  entity, note, onClose, onSaved,
}: {
  entity: HaEntity;
  note: EntityNoteRow | undefined;
  onClose: () => void;
  onSaved: () => void;
}) {
  const [label, setLabel] = useState(note?.label ?? '');
  const [category, setCategory] = useState(note?.category ?? '');
  const [tagsText, setTagsText] = useState((note?.tags ?? []).join(', '));
  const [noteText, setNoteText] = useState(note?.note ?? '');
  const [busy, setBusy] = useState(false);

  async function save() {
    setBusy(true);
    try {
      const tags = tagsText.split(',').map((t) => t.trim()).filter(Boolean);
      await api.saveNote(entity.entityId, { label, category, tags, note: noteText });
      onSaved();
      onClose();
    } catch (e) {
      alert(`save failed: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  async function quickSetLight() {
    setCategory('light');
    setBusy(true);
    try {
      await api.saveNote(entity.entityId, { category: 'light' });
      onSaved();
    } finally { setBusy(false); }
  }

  return (
    <div style={{ marginTop: 8, paddingTop: 8, borderTop: '1px dashed var(--border)' }}>
      <div style={{ fontSize: 11, color: 'var(--muted)' }}>
        entity_id: <code>{entity.entityId}</code> · domain: <code>{entity.domain}</code>
      </div>
      <Row label="Label">
        <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={entity.friendlyName ?? ''} />
      </Row>
      <Row label="Category">
        <input value={category} onChange={(e) => setCategory(e.target.value)}
               placeholder={`(${entity.domain})`} list="cats" />
        <datalist id="cats">
          {['light', 'switch', 'fan', 'outlet', 'pump', 'sensor', 'thermostat',
            'motion', 'door', 'lock', 'speaker', 'camera', 'valve', 'temperature',
            'humidity', 'battery'].map((c) => <option key={c} value={c} />)}
        </datalist>
      </Row>
      <Row label="Tags (comma separated)">
        <input value={tagsText} onChange={(e) => setTagsText(e.target.value)}
               placeholder="kitchen, dimmable, rgb" />
      </Row>
      <Row label="Note">
        <textarea value={noteText} onChange={(e) => setNoteText(e.target.value)}
                  rows={2} style={{ width: '100%' }} />
      </Row>
      <div style={{ display: 'flex', gap: 6, marginTop: 6, flexWrap: 'wrap' }}>
        {entity.domain === 'switch' && category !== 'light' && (
          <button onClick={quickSetLight} disabled={busy}
                  title="Mark this switch as actually a light">
            this is a light
          </button>
        )}
        <span style={{ flex: 1 }} />
        <button onClick={onClose} disabled={busy}>Cancel</button>
        <button onClick={save} disabled={busy}>Save</button>
      </div>
    </div>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label style={{ display: 'block', marginTop: 4, fontSize: 12 }}>
      <div style={{ color: 'var(--muted)', marginBottom: 2 }}>{label}</div>
      <div>{children}</div>
      <style>{`label > div > input,
                label > div > textarea { width: 100%; padding: 4px; font-size: 13px; }`}</style>
    </label>
  );
}

function parseAttr(attrsJson: string | null, key: string): unknown {
  if (!attrsJson) return null;
  try { return JSON.parse(attrsJson)[key]; } catch { return null; }
}

function capitalise(s: string) {
  return s.charAt(0).toUpperCase() + s.slice(1);
}
