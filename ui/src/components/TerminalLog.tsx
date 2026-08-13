import React, { useRef, useState, useEffect } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ClusterEvent } from '../hooks/useClusterStream';
import { Activity, Save, ServerCrash,
  ArrowUpCircle, ArrowDownCircle, Trash2, Layers, Filter
} from 'lucide-react';

interface TerminalLogProps {
  events: ClusterEvent[];
}

type FilterType = 'ALL' | 'OPERATION' | 'SST_FLUSH' | 'NODE_STATUS' | 'COMPACTION';

const OP_COLORS: Record<string, string> = {
  PUT:    '#10b981',
  GET:    '#60a5fa',
  DELETE: '#f87171',
};


function getIcon(ev: ClusterEvent) {
  if (ev.type === 'OPERATION') {
    if (ev.op === 'PUT')    return <ArrowUpCircle size={13} style={{ color: OP_COLORS.PUT }} />;
    if (ev.op === 'DELETE') return <Trash2 size={13} style={{ color: OP_COLORS.DELETE }} />;
    return <ArrowDownCircle size={13} style={{ color: OP_COLORS.GET }} />;
  }
  if (ev.type === 'SST_FLUSH')   return <Save size={13} style={{ color: '#14b8a6' }} />;
  if (ev.type === 'NODE_STATUS') return <ServerCrash size={13} style={{ color: '#f59e0b' }} />;
  if (ev.type === 'COMPACTION')  return <Layers size={13} style={{ color: '#a78bfa' }} />;
  return <Activity size={13} style={{ color: 'var(--color-text-muted)' }} />;
}

function getLabel(ev: ClusterEvent): { text: string; color?: string } {
  switch (ev.type) {
    case 'OPERATION': {
      const c = ev.success ? OP_COLORS[ev.op] ?? '' : '#f87171';
      const status = ev.success ? 'OK' : 'FAIL';
      return {
        text: `${ev.op} ${ev.key.length > 18 ? ev.key.slice(0,18)+'…' : ev.key} → ${ev.nodeId} [${status}]`,
        color: c,
      };
    }
    case 'SST_FLUSH':
      return { text: `FLUSH on ${ev.nodeId} · ${ev.extra?.sstableCount ?? '?'} SSTables total`, color: '#14b8a6' };
    case 'NODE_STATUS':
      return { text: `${ev.nodeId} → ${ev.extra?.status ?? '?'} (${ev.extra?.role ?? ''})`, color: '#f59e0b' };
    case 'COMPACTION':
      return { text: `COMPACTION on ${ev.nodeId} · ${ev.extra?.sstableCount ?? '?'} SSTables after`, color: '#a78bfa' };
    default:
      return { text: `${ev.type} from ${ev.nodeId}` };
  }
}

export const TerminalLog: React.FC<TerminalLogProps> = ({ events }) => {
  const [filter, setFilter] = useState<FilterType>('ALL');
  const [autoScroll, setAutoScroll] = useState(true);
  const scrollRef = useRef<HTMLDivElement>(null);

  const filtered = events.filter(e => {
    if (e.type === 'MEMTABLE') return false; // always hide low-level noise
    if (filter === 'ALL') return true;
    return e.type === filter;
  });

  // Auto-scroll to top (newest first)
  useEffect(() => {
    if (autoScroll && scrollRef.current) {
      scrollRef.current.scrollTop = 0;
    }
  }, [events, autoScroll]);

  const filters: { key: FilterType; label: string }[] = [
    { key: 'ALL', label: 'All' },
    { key: 'OPERATION', label: 'Ops' },
    { key: 'SST_FLUSH', label: 'Flush' },
    { key: 'COMPACTION', label: 'Compact' },
    { key: 'NODE_STATUS', label: 'Status' },
  ];

  return (
    <div className="glass-card rounded-xl border border-[var(--color-border)] flex flex-col h-full overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between px-4 pt-3 pb-2 border-b border-[var(--color-border)] flex-shrink-0">
        <h3 className="text-sm font-semibold text-[var(--color-text-primary)] flex items-center gap-2">
          <Activity size={14} className="text-[var(--color-brand-500)]" />
          Activity Stream
          <span className="ml-1 text-[10px] bg-[var(--color-brand-500)] text-white rounded-full px-1.5 py-0.5 font-bold">
            {filtered.length}
          </span>
        </h3>
        <div className="flex items-center gap-1.5">
          <Filter size={11} className="text-[var(--color-text-muted)]" />
          {filters.map(f => (
            <button
              key={f.key}
              onClick={() => setFilter(f.key)}
              className="text-[10px] px-2 py-0.5 rounded-full font-semibold border transition-all"
              style={{
                borderColor: filter === f.key ? 'var(--color-brand-500)' : 'var(--color-border)',
                color: filter === f.key ? 'var(--color-brand-500)' : 'var(--color-text-muted)',
                background: filter === f.key ? 'rgba(20,184,166,0.1)' : 'transparent',
              }}
            >
              {f.label}
            </button>
          ))}
        </div>
      </div>

      {/* Stream */}
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto px-3 py-2 space-y-0.5 custom-scrollbar font-mono text-xs"
        onMouseEnter={() => setAutoScroll(false)}
        onMouseLeave={() => setAutoScroll(true)}
      >
        <AnimatePresence initial={false}>
          {filtered.map((ev) => {
            const { text, color } = getLabel(ev);
            const latency = ev.latencyMs ?? 0;
            const maxLatBar = 200; // ms cap for bar width
            const barW = Math.min(100, (latency / maxLatBar) * 100);
            const ts = (() => {
              try {
                const d = new Date(ev.timestampMs || Date.now());
                if (isNaN(d.getTime())) return '??:??:??';
                return d.toISOString().split('T')[1].slice(0, 8);
              } catch { return '??:??:??'; }
            })();

            return (
              <motion.div
                key={ev.id}
                layout
                initial={{ opacity: 0, y: -8 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ duration: 0.18 }}
                className="flex items-center gap-2 py-1 px-2 rounded-lg hover:bg-[var(--color-surface-hover)] transition-colors group"
              >
                {/* Icon */}
                <div className="flex-shrink-0">{getIcon(ev)}</div>

                {/* Timestamp */}
                <span className="text-[10px] text-[var(--color-text-muted)] flex-shrink-0">{ts}</span>

                {/* Message */}
                <span className="flex-1 truncate" style={{ color: color || 'var(--color-text-secondary)' }}>
                  {text}
                </span>

                {/* Latency bar (only for OPERATION) */}
                {ev.type === 'OPERATION' && latency > 0 && (
                  <div className="flex items-center gap-1.5 flex-shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                    <div className="w-14 h-1.5 rounded-full bg-[var(--color-border)] overflow-hidden">
                      <div
                        className="h-full rounded-full"
                        style={{
                          width: `${barW}%`,
                          background: latency > 100 ? '#f59e0b' : latency > 50 ? '#60a5fa' : '#10b981',
                        }}
                      />
                    </div>
                    <span className="text-[9px] text-[var(--color-text-muted)] w-10 text-right">
                      {latency.toFixed(1)}ms
                    </span>
                  </div>
                )}
              </motion.div>
            );
          })}
        </AnimatePresence>

        {filtered.length === 0 && (
          <div className="flex flex-col items-center justify-center h-40 text-[var(--color-text-muted)]">
            <Activity size={28} className="mb-3 opacity-30" />
            <p className="text-xs">Waiting for cluster events…</p>
          </div>
        )}
      </div>

      {/* Footer: auto-scroll hint */}
      <div className="px-4 py-1.5 border-t border-[var(--color-border)] flex items-center justify-between flex-shrink-0">
        <span className="text-[10px] text-[var(--color-text-muted)]">
          {autoScroll ? '⬆ auto-scroll on' : '⏸ hover paused'}
        </span>
        <span className="text-[10px] text-[var(--color-text-muted)]">
          {events.length} total events
        </span>
      </div>
    </div>
  );
};
