import type { Area, HaEntity } from './types';

export default function Catalog({ areas, entities }: { areas: Area[]; entities: HaEntity[] }) {
  const byArea = new Map<string | null, HaEntity[]>();
  for (const e of entities) {
    const key = e.areaId ?? null;
    if (!byArea.has(key)) byArea.set(key, []);
    byArea.get(key)!.push(e);
  }
  const areaName = (id: string | null) =>
    id == null ? 'Unassigned' : areas.find((a) => a.id === id)?.name ?? id;

  // Show areas with at least one non-disabled entity, sorted alphabetically.
  const order = [...byArea.keys()].sort((a, b) =>
    areaName(a).localeCompare(areaName(b)),
  );

  return (
    <div>
      <p style={{ color: '#666' }}>
        {areas.length} areas · {entities.length} entities (live)
      </p>
      {order.map((areaId) => {
        const ents = byArea.get(areaId)!;
        return (
          <section key={areaId ?? 'unassigned'} style={{ marginTop: '1.5rem' }}>
            <h2 style={{ borderBottom: '1px solid #ddd', paddingBottom: 4, margin: 0 }}>
              {areaName(areaId)} <small style={{ color: '#999' }}>({ents.length})</small>
            </h2>
            <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
              {ents
                .slice()
                .sort((a, b) => (a.name ?? a.entityId).localeCompare(b.name ?? b.entityId))
                .map((e) => (
                  <li
                    key={e.entityId}
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      padding: '4px 0',
                      borderBottom: '1px solid #f3f3f3',
                    }}
                  >
                    <span>
                      <code style={{ color: '#888' }}>{e.domain}</code>{' '}
                      {e.name ?? e.entityId}
                    </span>
                    <span style={{ color: stateColor(e.state) }}>{e.state ?? '—'}</span>
                  </li>
                ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function stateColor(s: string | null): string {
  if (s == null) return '#aaa';
  if (s === 'on' || s === 'home' || s === 'open') return '#2a7';
  if (s === 'off' || s === 'not_home' || s === 'closed') return '#666';
  if (s === 'unavailable' || s === 'unknown') return '#c33';
  return '#444';
}
