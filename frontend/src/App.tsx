import { useEffect, useState } from 'react';
import { api, openWs } from './api';
import type { Area, HaEntity, WsEvent } from './types';
import Catalog from './Catalog';
import Chat from './Chat';
import Automations from './Automations';

type Tab = 'areas' | 'chat' | 'automations';

export default function App() {
  const [tab, setTab] = useState<Tab>('areas');
  const [areas, setAreas] = useState<Area[]>([]);
  const [entities, setEntities] = useState<HaEntity[]>([]);
  const [events, setEvents] = useState<WsEvent[]>([]);
  const [error, setError] = useState<string | null>(null);

  // Initial load.
  useEffect(() => {
    Promise.all([api.areas(), api.entities()])
      .then(([a, e]) => {
        setAreas(a);
        setEntities(e);
      })
      .catch((err) => setError(String(err)));
  }, []);

  // Subscribe to live events.
  useEffect(() => {
    const ws = openWs();
    ws.onmessage = (m) => {
      try {
        const ev = JSON.parse(m.data) as WsEvent;
        setEvents((prev) => [...prev.slice(-49), ev]);
        if (ev.type === 'state_changed') {
          setEntities((prev) => {
            const i = prev.findIndex((x) => x.entityId === ev.entityId);
            if (i < 0) return prev;
            const copy = prev.slice();
            copy[i] = { ...copy[i], state: ev.state };
            return copy;
          });
        } else if (ev.type === 'entity_removed') {
          setEntities((prev) => prev.filter((x) => x.entityId !== ev.entityId));
        }
      } catch {
        /* ignore */
      }
    };
    return () => ws.close();
  }, []);

  if (error) return <pre style={{ color: 'crimson', padding: 16 }}>{error}</pre>;

  return (
    <main style={{ fontFamily: 'system-ui, sans-serif', padding: '1.5rem', maxWidth: 1080, margin: '0 auto' }}>
      <header style={{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 16 }}>
        <h1 style={{ margin: 0 }}>HA Assistant</h1>
        <nav style={{ display: 'flex', gap: 4 }}>
          {(['areas', 'chat', 'automations'] as const).map((t) => (
            <button
              key={t}
              onClick={() => setTab(t)}
              style={{
                padding: '6px 12px',
                background: tab === t ? '#333' : '#eee',
                color: tab === t ? '#fff' : '#333',
                border: 'none',
                borderRadius: 4,
                cursor: 'pointer',
                textTransform: 'capitalize',
              }}
            >
              {t}
            </button>
          ))}
        </nav>
      </header>
      {tab === 'areas' && <Catalog areas={areas} entities={entities} />}
      {tab === 'chat' && <Chat events={events} />}
      {tab === 'automations' && <Automations />}
    </main>
  );
}
