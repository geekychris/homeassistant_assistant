import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { ChatMessageRow, ConversationSession, TurnStats, WsEvent } from './types';
import ToolMessage from './ToolMessage';

export default function Chat({ events }: { events: WsEvent[] }) {
  const [sessions, setSessions] = useState<ConversationSession[]>([]);
  const [active, setActive] = useState<number | null>(null);
  const [messages, setMessages] = useState<ChatMessageRow[]>([]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [awaiting, setAwaiting] = useState(false);
  const [streaming, setStreaming] = useState(''); // live token-stream text for the in-flight turn
  // Stats keyed by assistant ChatMessage.id once we know it (or 'latest' as a fallback).
  const [statsByMsgId, setStatsByMsgId] = useState<Record<string, TurnStats>>({});
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
    setStreaming('');
  }, [active]);

  // React to WS events for the active session.
  useEffect(() => {
    if (active == null) return;
    const last = events[events.length - 1];
    if (!last || (('sessionId' in last) && last.sessionId !== active)) return;
    if (last.type === 'assistant_token') {
      setStreaming((s) => s + last.token);
    } else if (last.type === 'assistant_message') {
      setStreaming('');
      api.messages(active).then((msgs) => {
        setMessages(msgs);
        // Pin the stats to the most recent assistant message id.
        if (last.stats) {
          const lastAssistant = [...msgs].reverse().find((m) => m.role === 'assistant');
          if (lastAssistant) setStatsByMsgId((prev) => ({ ...prev, [String(lastAssistant.id)]: last.stats! }));
        }
      });
      setAwaiting(false);
    } else if (last.type === 'ask_user') {
      setStreaming('');
      api.messages(active).then((msgs) => {
        setMessages(msgs);
        if (last.stats) {
          const lastAssistant = [...msgs].reverse().find((m) => m.role === 'assistant');
          if (lastAssistant) setStatsByMsgId((prev) => ({ ...prev, [String(lastAssistant.id)]: last.stats! }));
        }
      });
      setAwaiting(true);
    }
  }, [events, active]);

  // Auto-scroll on new messages.
  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight });
  }, [messages, streaming]);

  async function onSend(text: string = input) {
    const t = text.trim();
    if (!t || active == null || busy) return;
    setInput('');
    setBusy(true);
    setStreaming('');
    try {
      // Optimistically append the user message.
      setMessages((m) => [
        ...m,
        {
          id: -Date.now(),
          sessionId: active,
          role: 'user',
          content: t,
          toolName: null,
          toolCallsJson: null,
          createdAt: new Date().toISOString(),
        },
      ]);
      const r = await api.send(active, t);
      setAwaiting(r.awaitingUser);
      setStreaming('');
      const msgs = await api.messages(active);
      setMessages(msgs);
      const lastAssistant = [...msgs].reverse().find((m) => m.role === 'assistant');
      if (lastAssistant) {
        setStatsByMsgId((prev) => ({
          ...prev,
          [String(lastAssistant.id)]: {
            llmCalls: r.llmCalls, llmMs: r.llmMs,
            toolCalls: r.toolCalls, toolMs: r.toolMs, totalMs: r.totalMs,
          },
        }));
      }
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
    setStreaming('');
  }

  // ─── voice ────────────────────────────────────────────────────────────
  const [voiceOut, setVoiceOut] = useState(false);
  const [listening, setListening] = useState(false);
  const recRef = useRef<any>(null);

  function toggleMic() {
    if (listening) {
      recRef.current?.stop();
      return;
    }
    const SR = (window as any).SpeechRecognition || (window as any).webkitSpeechRecognition;
    if (!SR) {
      alert('Speech recognition not supported in this browser. Try Chrome.');
      return;
    }
    const r = new SR();
    r.lang = 'en-US';
    r.interimResults = false;
    r.maxAlternatives = 1;
    r.onresult = (e: any) => {
      const text = e.results[0][0].transcript;
      setListening(false);
      onSend(text);
    };
    r.onerror = (e: any) => { console.warn('mic error', e); setListening(false); };
    r.onend = () => setListening(false);
    recRef.current = r;
    setListening(true);
    r.start();
  }

  // Speak assistant replies if voiceOut is on.
  useEffect(() => {
    if (!voiceOut) return;
    const last = events[events.length - 1];
    if (!last || !('sessionId' in last) || last.sessionId !== active) return;
    if (last.type === 'assistant_message' && last.content) speak(last.content);
    else if (last.type === 'ask_user' && last.question) speak(last.question);
  }, [events, voiceOut, active]);

  return (
    <div style={{ display: 'flex', gap: 16, height: '70vh', flexWrap: 'wrap' }}>
      <aside style={{ width: 180, borderRight: '1px solid #ddd', paddingRight: 12, minWidth: 0 }}>
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
      <div style={{ flex: 1, minWidth: 280, display: 'flex', flexDirection: 'column' }}>
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
            .filter((m) => !(m.role === 'assistant' && (!m.content || !m.content.trim())))
            .map((m) => {
              if (m.role === 'tool') {
                return <ToolMessage key={m.id} toolName={m.toolName} rawJson={m.content} />;
              }
              const stats = m.role === 'assistant' ? statsByMsgId[String(m.id)] : undefined;
              return (
                <div key={m.id} style={{ marginBottom: 12 }}>
                  <div style={{ fontSize: 11, color: '#888' }}>{m.role}</div>
                  <div style={{ whiteSpace: 'pre-wrap', fontSize: 14 }}>{m.content}</div>
                  {stats && <StatsFooter stats={stats} />}
                </div>
              );
            })}
          {streaming && (
            <div style={{ marginBottom: 12, opacity: 0.85 }}>
              <div style={{ fontSize: 11, color: '#888' }}>assistant · streaming</div>
              <div style={{ whiteSpace: 'pre-wrap' }}>
                {streaming}
                <span className="caret">▍</span>
              </div>
            </div>
          )}
          {busy && !streaming && <div style={{ color: '#888' }}>thinking…</div>}
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 8, alignItems: 'center' }}>
          <button
            onClick={toggleMic}
            title="Push-to-talk (browser SpeechRecognition; uses cloud STT in Chrome)"
            style={{
              padding: '8px 12px',
              background: listening ? '#c33' : '#eee',
              color: listening ? '#fff' : '#333',
              border: 'none',
              borderRadius: 4,
              cursor: 'pointer',
            }}
          >
            {listening ? '● rec' : '🎤'}
          </button>
          <input
            type="text"
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => e.key === 'Enter' && onSend()}
            placeholder={awaiting ? 'Answer the question above…' : 'Ask about your home…'}
            disabled={busy}
            style={{ flex: 1, padding: 8, fontSize: 14, minWidth: 0 }}
          />
          <button onClick={() => onSend()} disabled={busy || !input.trim()}>
            Send
          </button>
          <label style={{ fontSize: 12, color: '#666', display: 'flex', alignItems: 'center', gap: 4 }}>
            <input
              type="checkbox"
              checked={voiceOut}
              onChange={(e) => setVoiceOut(e.target.checked)}
            />
            🔊
          </label>
        </div>
      </div>
    </div>
  );
}

function StatsFooter({ stats }: { stats: TurnStats }) {
  const llmPct = stats.totalMs > 0 ? Math.round((stats.llmMs / stats.totalMs) * 100) : 0;
  const overhead = Math.max(0, stats.totalMs - stats.llmMs - stats.toolMs);
  return (
    <div
      title={`Wall-clock for this reply broken down by where the time went.\n` +
             `LLM:   ${stats.llmCalls} call${stats.llmCalls === 1 ? '' : 's'}, ${fmt(stats.llmMs)}\n` +
             `Tools: ${stats.toolCalls} call${stats.toolCalls === 1 ? '' : 's'}, ${fmt(stats.toolMs)}\n` +
             `Other: ${fmt(overhead)} (DB writes, WS broadcasts, etc.)\n` +
             `Total: ${fmt(stats.totalMs)}`}
      style={{
        marginTop: 4, fontSize: 11, color: 'var(--muted)',
        display: 'flex', gap: 8, flexWrap: 'wrap', alignItems: 'center',
      }}>
      <span>⚡ {stats.llmCalls} LLM × {fmt(stats.llmMs)}{llmPct ? ` (${llmPct}%)` : ''}</span>
      {stats.toolCalls > 0 && <span>🔧 {stats.toolCalls} tool × {fmt(stats.toolMs)}</span>}
      <span>· {fmt(stats.totalMs)} total</span>
    </div>
  );
}

function fmt(ms: number): string {
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function speak(text: string) {
  if (!('speechSynthesis' in window)) return;
  // Cancel anything in flight so consecutive replies don't queue up.
  window.speechSynthesis.cancel();
  const u = new SpeechSynthesisUtterance(text);
  u.rate = 1.05;
  window.speechSynthesis.speak(u);
}
