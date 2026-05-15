import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { Area, EntityNoteRow, HaEntity } from './types';

/**
 * Areas tab.
 *
 * - Each card is one HA *device* (entities sharing a device_id), so a camera's
 *   camera+battery+motion+last_activity entities stay together rather than
 *   scattering across the sort order.
 * - Per-entity inline controls live inside the device card.
 * - Edit (gear) opens a per-entity panel for label/category/tags/note.
 * - Section headers collapse with click; collapsed state persists in localStorage.
 * - "Hide unavailable" filters out anything whose state is 'unavailable'.
 * - Group selector flips between "by area" (default) and "by category", the
 *   latter being the categorisation worksheet.
 */
export default function Catalog({ areas, entities }: { areas: Area[]; entities: HaEntity[] }) {
  const [notes, setNotes] = useState<Record<string, EntityNoteRow>>({});
  const [groupBy, setGroupBy] = useState<'area' | 'category'>('area');
  const [filter, setFilter] = useState<string>('');
  const [hideUnavailable, setHideUnavailable] = useState<boolean>(() =>
    localStorage.getItem('haasst.hideUnavailable') === 'true',
  );
  const [collapsed, setCollapsed] = useState<Set<string>>(() => {
    try {
      const stored = localStorage.getItem('haasst.collapsed');
      return new Set(stored ? JSON.parse(stored) : []);
    } catch { return new Set(); }
  });
  const [editing, setEditing] = useState<string | null>(null);

  useEffect(() => { localStorage.setItem('haasst.hideUnavailable', String(hideUnavailable)); }, [hideUnavailable]);
  useEffect(() => { localStorage.setItem('haasst.collapsed', JSON.stringify([...collapsed])); }, [collapsed]);

  async function reloadNotes() {
    const list = await api.notes();
    const map: Record<string, EntityNoteRow> = {};
    list.forEach((n) => { map[n.entityId] = n; });
    setNotes(map);
  }
  useEffect(() => { reloadNotes(); }, []);

  const augmented = useMemo(() =>
    entities.map((e) => {
      const n = notes[e.entityId];
      const category = (n?.category ?? e.domain).toLowerCase();
      return { e, n, category };
    }),
  [entities, notes]);

  const categoryOptions = useMemo(() => {
    const s = new Set<string>();
    augmented.forEach((a) => s.add(a.category));
    return [...s].sort();
  }, [augmented]);

  const visible = augmented.filter((a) => {
    if (hideUnavailable && a.e.state === 'unavailable') return false;
    if (filter && a.category !== filter
        && !(a.e.friendlyName ?? a.e.entityId).toLowerCase().includes(filter.toLowerCase())) {
      return false;
    }
    return true;
  });

  // Group → section → devices → entities
  const sections = new Map<string, typeof visible>();
  for (const a of visible) {
    const key = groupBy === 'area'
      ? (a.e.areaId ?? '__unassigned__')
      : a.category;
    if (!sections.has(key)) sections.set(key, []);
    sections.get(key)!.push(a);
  }
  const areaName = (id: string) =>
    id === '__unassigned__' ? 'Unassigned'
      : areas.find((a) => a.id === id)?.name ?? id;
  const sectionLabel = (id: string) => groupBy === 'area' ? areaName(id) : capitalise(id);

  const sortedSectionKeys = [...sections.keys()].sort((a, b) =>
    sectionLabel(a).localeCompare(sectionLabel(b)));

  function toggleCollapsed(key: string) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  }

  return (
    <div>
      <div style={{ display: 'flex', gap: 8, alignItems: 'center', marginBottom: 12, flexWrap: 'wrap', fontSize: 13 }}>
        <span style={{ color: 'var(--muted)' }}>{entities.length} entities</span>
        <span style={{ color: 'var(--muted)' }}>·</span>
        <span>group:</span>
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
        <label style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 12, cursor: 'pointer' }}>
          <input type="checkbox" checked={hideUnavailable}
                 onChange={(e) => setHideUnavailable(e.target.checked)} />
          hide unavailable
        </label>
        <span style={{ flex: 1 }} />
        <button onClick={() => setCollapsed(new Set())} title="Expand all">expand all</button>
        <button onClick={() => setCollapsed(new Set(sortedSectionKeys))} title="Collapse all">collapse all</button>
      </div>

      {sortedSectionKeys.map((sectionKey) => {
        const items = sections.get(sectionKey)!;
        const devices = groupByDevice(items);
        const isCollapsed = collapsed.has(sectionKey);
        return (
          <section key={sectionKey} style={{ marginBottom: 16 }}>
            <h2 onClick={() => toggleCollapsed(sectionKey)}
                style={{ borderBottom: '1px solid var(--border)', paddingBottom: 4, margin: '0 0 8px',
                         fontSize: 16, cursor: 'pointer', userSelect: 'none',
                         display: 'flex', alignItems: 'center', gap: 6 }}>
              <span style={{ width: 14, display: 'inline-block' }}>{isCollapsed ? '▸' : '▾'}</span>
              {sectionLabel(sectionKey)}
              <small style={{ color: 'var(--muted)', fontWeight: 'normal' }}>
                ({devices.length} {devices.length === 1 ? 'device' : 'devices'},
                {' '}{items.length} {items.length === 1 ? 'entity' : 'entities'})
              </small>
            </h2>
            {!isCollapsed && (
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 8 }}>
                {devices.map((dev) => (
                  <DeviceCard
                    key={dev.key}
                    device={dev}
                    editing={editing}
                    onToggleEdit={(eid) => setEditing(editing === eid ? null : eid)}
                    onChange={reloadNotes}
                  />
                ))}
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}

/** Group a section's items by device_id; entities with no device_id are their own group. */
function groupByDevice(items: { e: HaEntity; n: EntityNoteRow | undefined; category: string }[]) {
  const map = new Map<string, { e: HaEntity; n: EntityNoteRow | undefined; category: string }[]>();
  for (const item of items) {
    const key = item.e.deviceId ?? `__entity__${item.e.entityId}`;
    if (!map.has(key)) map.set(key, []);
    map.get(key)!.push(item);
  }
  const devices = [...map.entries()].map(([key, ents]) => ({
    key,
    name: deriveDeviceName(ents),
    ents: ents.slice().sort((a, b) =>
      categoryOrder(a.category) - categoryOrder(b.category)
      || (a.e.friendlyName ?? a.e.entityId).localeCompare(b.e.friendlyName ?? b.e.entityId)),
  }));
  return devices.sort((a, b) => a.name.localeCompare(b.name));
}

/** Devices with multiple entities get a derived name from the longest common prefix. */
function deriveDeviceName(ents: { e: HaEntity; n: EntityNoteRow | undefined }[]): string {
  if (ents.length === 1) {
    return ents[0].n?.label || ents[0].e.friendlyName || ents[0].e.entityId;
  }
  const names = ents.map((x) => x.e.friendlyName || x.e.entityId);
  let i = 0;
  const min = Math.min(...names.map((n) => n.length));
  while (i < min && names.every((n) => n[i] === names[0][i])) i++;
  let prefix = names[0].slice(0, i).trim().replace(/[\s\-_·:]+$/, '');
  if (prefix.length >= 3) return prefix;
  return names[0];
}

/** Sort key so the main control (light/switch/etc.) sits above sub-sensors in a device card. */
function categoryOrder(c: string): number {
  if (['light', 'switch', 'fan', 'outlet', 'pump', 'valve', 'lock', 'media_player', 'camera', 'climate', 'thermostat'].includes(c)) return 0;
  if (c === 'cover' || c === 'door' || c === 'window') return 1;
  return 2; // sensors and friends last
}

function DeviceCard({
  device, editing, onToggleEdit, onChange,
}: {
  device: { key: string; name: string; ents: { e: HaEntity; n: EntityNoteRow | undefined; category: string }[] };
  editing: string | null;
  onToggleEdit: (entityId: string) => void;
  onChange: () => void;
}) {
  const multi = device.ents.length > 1;
  const primary = device.ents[0];
  const primaryIcon = iconFor(primary.category, primary.e.deviceClass, primary.e.domain);

  return (
    <div style={{ border: '1px solid var(--border)', borderRadius: 6, padding: 8, background: 'var(--tile)' }}>
      {multi && (
        <div style={{ fontWeight: 600, fontSize: 14, marginBottom: 4, display: 'flex', alignItems: 'center', gap: 6 }}>
          <span style={{ fontSize: 18 }}>{primaryIcon}</span>
          <span style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {device.name}
          </span>
          <small style={{ color: 'var(--muted)', fontWeight: 'normal' }}>({device.ents.length})</small>
        </div>
      )}
      {device.ents.map((item, idx) => (
        <EntityRow
          key={item.e.entityId}
          item={item}
          first={idx === 0}
          showHeader={!multi}
          editing={editing === item.e.entityId}
          onToggleEdit={() => onToggleEdit(item.e.entityId)}
          onChange={onChange}
        />
      ))}
    </div>
  );
}

function EntityRow({
  item, first, showHeader, editing, onToggleEdit, onChange,
}: {
  item: { e: HaEntity; n: EntityNoteRow | undefined; category: string };
  first: boolean;
  showHeader: boolean;
  editing: boolean;
  onToggleEdit: () => void;
  onChange: () => void;
}) {
  const { e, n, category } = item;
  const headline = n?.label || e.friendlyName || e.entityId;
  const icon = iconFor(category, e.deviceClass, e.domain);
  return (
    <div style={{ paddingTop: first ? 0 : 6, marginTop: first ? 0 : 6,
                  borderTop: first ? 'none' : '1px solid var(--border)' }}>
      <div style={{ display: 'flex', justifyContent: 'space-between', gap: 6, alignItems: 'center' }}>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center', minWidth: 0, flex: 1 }}>
          {showHeader && <span style={{ fontSize: 18 }}>{icon}</span>}
          {!showHeader && <span style={{ fontSize: 14, opacity: 0.6 }}>{icon}</span>}
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontWeight: showHeader ? 600 : 400, fontSize: showHeader ? 14 : 13,
                          whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {headline}
            </div>
            <div style={{ fontSize: 10, color: 'var(--muted)' }}>
              {category}{e.deviceClass && e.deviceClass !== category ? ` · ${e.deviceClass}` : ''}
            </div>
          </div>
        </div>
        <button onClick={onToggleEdit}
                style={{ background: 'transparent', border: 'none', color: 'var(--muted)',
                         cursor: 'pointer', fontSize: 14, padding: 2 }}>
          {editing ? '×' : '⚙'}
        </button>
      </div>
      <div style={{ marginTop: 4 }}>
        <Control entity={e} category={category} />
      </div>
      {n?.tags && n.tags.length > 0 && (
        <div style={{ marginTop: 4, display: 'flex', flexWrap: 'wrap', gap: 3 }}>
          {n.tags.map((t) => (
            <span key={t} style={{ fontSize: 10, padding: '0 6px', border: '1px solid var(--border)',
                                   borderRadius: 8, color: 'var(--muted)' }}>{t}</span>
          ))}
        </div>
      )}
      {editing && <EditPanel entity={e} note={n} onClose={onToggleEdit} onSaved={onChange} />}
    </div>
  );
}

function Control({ entity, category }: { entity: HaEntity; category: string }) {
  const [busy, setBusy] = useState(false);
  const state = entity.state ?? '';
  const isOn = state === 'on' || state === 'home' || state === 'open' || state === 'detected';
  const isReadOnly = isReadOnlyKind(entity.domain, category);

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
            padding: '3px 12px', borderRadius: 14, border: '1px solid var(--border)',
            background: isOn ? '#2a7' : 'var(--tile)', color: isOn ? '#fff' : 'inherit',
            minWidth: 56, fontSize: 12,
          }}>
          {isOn ? 'ON' : 'OFF'}
        </button>
        <span style={{ fontSize: 11, color: state === 'unavailable' ? '#c33' : 'var(--muted)' }}>{state || '—'}</span>
      </div>
      {supportsBrightness && (
        <div style={{ marginTop: 4, display: 'flex', gap: 6, alignItems: 'center' }}>
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
          <span style={{ fontSize: 10, color: 'var(--muted)', width: 32, textAlign: 'right' }}>
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

function capitalise(s: string) { return s.charAt(0).toUpperCase() + s.slice(1); }

/** Read-only kinds (no control). Sensors mostly. */
function isReadOnlyKind(domain: string, category: string): boolean {
  if (['sensor', 'binary_sensor', 'weather', 'sun', 'person', 'device_tracker', 'zone'].includes(domain)) return true;
  if (['sensor', 'temperature', 'humidity', 'battery', 'motion', 'door', 'window',
       'illuminance', 'pressure', 'energy', 'power', 'gas', 'water'].includes(category)) return true;
  return false;
}

/** Pick an emoji for an entity, in priority order: category override → device_class → domain. */
function iconFor(category: string, deviceClass: string | null, domain: string): string {
  // category-driven (most specific — user has told us what this is)
  const byCat: Record<string, string> = {
    light: '💡', switch: '🔘', fan: '🌀', outlet: '🔌', pump: '⚙️',
    valve: '🚰', lock: '🔒', speaker: '🔊', camera: '📷', thermostat: '🌡️',
    motion: '🏃', door: '🚪', window: '🪟', sensor: '📊', temperature: '🌡️',
    humidity: '💧', battery: '🔋', illuminance: '☀️', pressure: '📈',
    energy: '⚡', power: '⚡', gas: '🔥', water: '💧',
  };
  if (byCat[category]) return byCat[category];
  if (deviceClass && byCat[deviceClass]) return byCat[deviceClass];
  // domain fallback
  const byDomain: Record<string, string> = {
    light: '💡', switch: '🔘', fan: '🌀', sensor: '📊', binary_sensor: '🔘',
    camera: '📷', media_player: '▶️', cover: '🪟', climate: '🌡️', lock: '🔒',
    weather: '☁️', person: '👤', device_tracker: '📍', zone: '📍',
    sun: '☀️', input_boolean: '◯', input_number: '🔢', input_select: '☰',
    automation: '⚡', script: '📜', scene: '🎬', timer: '⏱️',
    update: '⬆️', button: '🔘',
  };
  return byDomain[domain] ?? '•';
}
