import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { ChatMessageRow, ConversationSession, WsEvent } from './types';

export default function Chat({ events }: { events: WsEvent[] }) {
  const [sessions, setSessions] = useState<ConversationSession[]>([]);
  const [active, setActive] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessageRow[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [awaiting, setAwaiting] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  // Bootstrap: load sessions; create one if none exist.
  useEffect(() => {
    api.sessions().then(async (list) => {
      if (list.length === 0) {
        const s = await api.createSession('Default');
        setSessions([s]);
        setActive(s.id);
      } else {
        setSessions(list);
        setActive(list[list.length - 1].id);
      }
    });
  }, []);

  // Reload messages when active session changes.
  useEffect(() => {
    if (active == null) return;
    api.messages(active).then(setMessages);
  }, [active]);

  // React to WS ask_user / assistant_message events for the active session.
  useEffect(() => {
    if (active == null) return;
    const last = events[events.length - 1];
    if (!last) return;
    if (
      (last.type === 'ask_user' || last.type === 'assistant_message') &&
      last.sessionId === active
    ) {
      api.messages(active).then(setMessages);
      if (last.type === 'ask_user') setAwaiting(true);
      if (last.type === 'assistant_message') setAwaiting(false);
    }
  }, [events, active]);

  // Auto-scroll on new messages.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages]);

  async function onSend() {
    if (!input.trim() || active == null || busy) return;
    const text = input;
    setInput('');
    setBusy(true);
    try {
      // Optimistically append the user message.
      setMessages((m) => [
        ...m,
        {
          id: -Date.now(),
          sessionId: active,
          role: 'user',
          content: text,
          toolName: null,
          toolCallsJson: null,
          createdAt: new Date().toISOString(),
        },
      ]);
      const r = await api.send(active, text);
      setAwaiting(r.awaitingUser);
      setMessages(await api.messages(active));
    } catch (e) {
      alert(`send failed: ${e}`);
    } finally {
      setBusy(false);
    }
  }

  async function newSession() {
    const s = await api.createSession(`Chat ${sessions.length + 1}`);
    setSessions([...sessions, s]);
    setActive(s.id);
    setMessages([]);
    setAwaiting(false);
  }

  return (
    <div style={{ display: 'flex', gap: 16, height: '70vh' }}>
      <aside style={{ width: 180, borderRight: '1px solid #ddd', paddingRight: 12 }}>
        <button onClick={newSession} style={{ width: '100%', marginBottom: 8 }}>
          + New chat
        </button>
        <ul style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {sessions.map((s) => (
            <li key={s.id}>
              <button
                onClick={() => setActive(s.id)}
                style={{
                  width: '100%',
                  textAlign: 'left',
                  padding: 6,
                  background: s.id === active ? '#eef' : 'transparent',
                  border: 'none',
                  cursor: 'pointer',
                }}
              >
                {s.title ?? `#${s.id}`}
              </button>
            </li>
          ))}
        </ul>
      </aside>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div
          ref={scrollRef}
          style={{
            flex: 1,
            overflowY: 'auto',
            padding: 8,
            border: '1px solid #ddd',
            borderRadius: 6,
            background: '#fafafa',
          }}
        >
          {messages
            .filter((m) => m.role !== 'tool' || (m.content && m.content.length < 400))
            .map((m) => (
              <div key={m.id} style={{ marginBottom: 12 }}>
                <div style={{ fontSize: 11, color: '#888' }}>
                  {m.role}
                  {m.toolName ? ` · ${m.toolName}` : ''}
                </div>
                <div
                  style={{
                    whiteSpace: 'pre-wrap',
                    fontFamily: m.role === 'tool' ? 'ui-monospace, monospace' : 'inherit',
                    color: m.role === 'tool' ? '#666' : 'inherit',
                    fontSize: m.role === 'tool' ? 12 : 14,
                  }}
                >
                  {m.content}
                </div>
              </div>
            ))}
          {busy && <div style={{ color: '#888' }}>thinking…</div>}
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 8 }}>
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && onSend()}
            placeholder={awaiting ? 'Answer the question above…' : 'Ask about your home…'}
            disabled={busy}
            style={{ flex: 1, padding: 8, fontSize: 14 }}
          />
          <button onClick={onSend} disabled={busy || !input.trim()}>
            Send
          </button>
        </div>
      </div>
    </div>
  );
}
