import { useEffect, useState } from 'react';

type Area = { id: string; name: string };
type HaEntity = {
  entityId: string;
  domain: string;
  name: string | null;
  areaId: string | null;
  state: string | null;
};

export default function App() {
  const [areas, setAreas] = useState<Area[]>([]);
  const [entities, setEntities] = useState<HaEntity[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([
      fetch('/api/areas').then((r) => r.json()),
      fetch('/api/entities').then((r) => r.json()),
    ])
      .then(([a, e]) => {
        setAreas(a);
        setEntities(e);
      })
      .catch((err) => setError(String(err)));
  }, []);

  if (error) return <pre style={{ color: 'crimson' }}>{error}</pre>;

  const byArea = new Map<string | null, HaEntity[]>();
  for (const e of entities) {
    const key = e.areaId ?? null;
    if (!byArea.has(key)) byArea.set(key, []);
    byArea.get(key)!.push(e);
  }
  const areaName = (id: string | null) =>
    id == null ? 'Unassigned' : areas.find((a) => a.id === id)?.name ?? id;

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '1.5rem', maxWidth: 960, margin: '0 auto' }}>
      <h1>HA Assistant</h1>
      <p style={{ color: '#666' }}>
        {areas.length} areas, {entities.length} entities
      </p>
      {[...byArea.entries()].map(([areaId, ents]) => (
        <section key={areaId ?? 'unassigned'} style={{ marginTop: '1.5rem' }}>
          <h2 style={{ borderBottom: '1px solid #ddd', paddingBottom: 4 }}>{areaName(areaId)}</h2>
          <ul style={{ listStyle: 'none', padding: 0 }}>
            {ents.map((e) => (
              <li key={e.entityId} style={{ display: 'flex', justifyContent: 'space-between', padding: '4px 0' }}>
                <span>
                  <code style={{ color: '#888' }}>{e.domain}</code> {e.name ?? e.entityId}
                </span>
                <span style={{ color: '#444' }}>{e.state ?? '—'}</span>
              </li>
            ))}
          </ul>
        </section>
      ))}
    </main>
  );
}
