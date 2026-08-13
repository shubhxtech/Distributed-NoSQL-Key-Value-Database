import React, { useMemo } from 'react';
import type { ClusterEvent, NodeState } from '../hooks/useClusterStream';
import { Zap, TrendingUp, Database, HardDrive, CheckCircle2, XCircle } from 'lucide-react';

interface StatsBarProps {
  events: ClusterEvent[];
  nodes: NodeState[];
  connected: boolean;
}

export const StatsBar: React.FC<StatsBarProps> = ({ events, nodes, connected }) => {
  const stats = useMemo(() => {
    const ops = events.filter(e => e.type === 'OPERATION');
    const puts = ops.filter(e => e.op === 'PUT').length;
    const gets = ops.filter(e => e.op === 'GET').length;
    const dels = ops.filter(e => e.op === 'DELETE').length;
    const failed = ops.filter(e => !e.success).length;
    const totalOps = ops.length;

    // Ops in last 10s
    const now = Date.now();
    const recent = ops.filter(e => (now - (e.timestampMs || now)) < 10_000);
    const opsPerSec = recent.length > 0 ? (recent.length / 10).toFixed(1) : '0.0';

    // Avg latency
    const avgMs = ops.length > 0
      ? (ops.reduce((s, e) => s + (e.latencyMs || 0), 0) / ops.length).toFixed(1)
      : '—';

    // Cluster SSTable total
    const totalSst = nodes.reduce((s, n) => s + (n.sstableCount || 0), 0);
    const upNodes = nodes.filter(n => n.status === 'UP').length;

    return { puts, gets, dels, failed, totalOps, opsPerSec, avgMs, totalSst, upNodes };
  }, [events, nodes]);

  const stat = (icon: React.ReactNode, label: string, value: string | number, color: string) => (
    <div className="flex items-center gap-2.5 px-4 py-3 bg-[var(--color-surface-hover)] rounded-xl border border-[var(--color-border)] min-w-[100px]">
      <div style={{ color }}>{icon}</div>
      <div>
        <div className="text-[10px] uppercase tracking-widest text-[var(--color-text-muted)] font-medium">{label}</div>
        <div className="text-base font-bold text-[var(--color-text-primary)] leading-tight">{value}</div>
      </div>
    </div>
  );

  return (
    <div className="flex flex-wrap gap-3 items-center">
      {stat(<Zap size={16}/>, 'ops/sec', stats.opsPerSec, '#f59e0b')}
      {stat(<TrendingUp size={16}/>, 'total ops', stats.totalOps, '#14b8a6')}
      {stat(<Database size={16}/>, 'put / get', `${stats.puts} / ${stats.gets}`, '#60a5fa')}
      {stat(<HardDrive size={16}/>, 'SSTables', stats.totalSst, '#a78bfa')}
      {stat(
        stats.failed > 0 ? <XCircle size={16}/> : <CheckCircle2 size={16}/>,
        'errors',
        stats.failed,
        stats.failed > 0 ? '#f87171' : '#10b981'
      )}
      {stat(<Zap size={16}/>, 'avg latency', stats.avgMs === '—' ? '—' : `${stats.avgMs}ms`, '#f59e0b')}

      {/* SSE status pill */}
      <div className={`ml-auto flex items-center gap-2 text-xs font-semibold px-3 py-2 rounded-full border ${
        connected
          ? 'text-[var(--color-success)] border-[var(--color-success)] bg-emerald-500/10'
          : 'text-[var(--color-error)] border-[var(--color-error)] bg-red-500/10'
      }`}>
        <span className={`w-2 h-2 rounded-full ${connected ? 'bg-[var(--color-success)] animate-pulse' : 'bg-[var(--color-error)]'}`}/>
        {connected ? 'SSE LIVE' : 'DISCONNECTED'}
      </div>
    </div>
  );
};
