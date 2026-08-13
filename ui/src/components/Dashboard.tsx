import { useState, useEffect } from 'react';
import { useClusterStream } from '../hooks/useClusterStream';
import { NodeCard } from './NodeCard';
import { TerminalLog } from './TerminalLog';
import { WritePath } from './WritePath';
import { StatsBar } from './StatsBar';
import { BurstTest } from './BurstTest';
import { Moon, Sun, Database, Network } from 'lucide-react';

export const Dashboard = () => {
  const { nodes, events, connected, activeClients, killNode, restartNode, triggerCompaction } = useClusterStream();
  const [isDark, setIsDark] = useState(true);

  useEffect(() => {
    if (isDark) document.documentElement.classList.add('dark');
    else document.documentElement.classList.remove('dark');
  }, [isDark]);

  return (
    <div className="min-h-screen bg-[var(--color-background)] p-4 md:p-6">

      {/* ── Top Navbar ── */}
      <nav className="glass-panel rounded-2xl mb-5 p-3 px-5 flex items-center justify-between sticky top-3 z-50">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-[var(--color-brand-500)] to-[var(--color-info)] flex items-center justify-center shadow-lg">
            <Database className="text-white" size={18} />
          </div>
          <div>
            <h1 className="text-lg font-bold text-gradient leading-tight">Antigravity KV</h1>
            <span className="text-[10px] text-[var(--color-text-muted)] font-medium tracking-widest uppercase">
              LSM Storage Engine · Day 8
            </span>
          </div>
        </div>

        <div className="flex items-center gap-3">
          <div className="hidden md:flex items-center gap-2 text-xs text-[var(--color-text-secondary)] bg-[var(--color-surface-hover)] px-3 py-1.5 rounded-full border border-[var(--color-border)]">
            <Network size={13} />
            <span>{activeClients} Observer{activeClients !== 1 ? 's' : ''}</span>
          </div>
          <button
            onClick={() => setIsDark(!isDark)}
            className="p-2 rounded-full bg-[var(--color-surface-hover)] border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)] transition-colors"
          >
            {isDark ? <Sun size={16} /> : <Moon size={16} />}
          </button>
        </div>
      </nav>

      {/* ── Stats Bar ── */}
      <div className="mb-5">
        <StatsBar events={events} nodes={nodes} connected={connected} />
      </div>

      {/* ── Write Path Diagram ── */}
      <div className="mb-5">
        <WritePath events={events} nodes={nodes} />
      </div>

      {/* ── Main 3-col Grid ── */}
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5" style={{ minHeight: 'calc(100vh - 420px)' }}>

        {/* Left: Node Cards (2 cols) */}
        <div className="lg:col-span-2 flex flex-col gap-5">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold text-[var(--color-text-primary)] flex items-center gap-2">
              <Network size={16} className="text-[var(--color-brand-500)]" />
              Cluster Nodes
            </h2>
            <span className="text-xs text-[var(--color-text-muted)] bg-[var(--color-surface-hover)] px-2 py-1 rounded-md border border-[var(--color-border)]">
              {nodes.filter(n => n.status === 'UP').length}/{nodes.length} UP
            </span>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {nodes.length === 0 && (
              <div className="col-span-full py-12 text-center text-[var(--color-text-muted)] glass-card rounded-xl">
                <Database className="mx-auto mb-3 opacity-30" size={32} />
                <p className="text-sm">Waiting for nodes to register…</p>
              </div>
            )}
            {[...nodes].sort((a, b) => a.id.localeCompare(b.id)).map(node => (
              <NodeCard
                key={node.id}
                node={node}
                onKill={killNode}
                onRestart={restartNode}
                onCompact={triggerCompaction}
              />
            ))}
          </div>

          {/* Burst Test below the node cards */}
          <BurstTest />
        </div>

        {/* Right: Activity stream */}
        <div className="lg:col-span-1" style={{ minHeight: 500 }}>
          <TerminalLog events={events} />
        </div>
      </div>
    </div>
  );
};
