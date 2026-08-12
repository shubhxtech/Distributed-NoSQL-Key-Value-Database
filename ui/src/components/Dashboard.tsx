import React, { useState, useEffect } from 'react';
import { useClusterStream } from '../hooks/useClusterStream';
import { NodeCard } from './NodeCard';
import { TerminalLog } from './TerminalLog';
import { Moon, Sun, Activity, Database, Network } from 'lucide-react';

export const Dashboard: React.FC = () => {
  const { nodes, events, connected, activeClients, killNode, restartNode } = useClusterStream();
  const [isDark, setIsDark] = useState(true);

  // Toggle Theme
  useEffect(() => {
    if (isDark) document.documentElement.classList.add('dark');
    else document.documentElement.classList.remove('dark');
  }, [isDark]);

  return (
    <div className="min-h-screen bg-[var(--color-bg-gradient-from)] p-4 md:p-8">
      {/* Top Navbar */}
      <nav className="glass-panel rounded-2xl mb-8 p-4 px-6 flex items-center justify-between sticky top-4 z-50">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-[var(--color-brand-500)] to-[var(--color-info)] flex items-center justify-center shadow-lg">
            <Database className="text-white" size={20} />
          </div>
          <div>
            <h1 className="text-xl font-bold text-gradient leading-tight">Antigravity KV</h1>
            <div className="flex items-center gap-2 text-xs text-[var(--color-text-muted)] font-medium tracking-wide">
              <span>LSM STORAGE ENGINE</span>
              <span>•</span>
              <span className="flex items-center gap-1">
                <span className={`w-2 h-2 rounded-full ${connected ? 'bg-[var(--color-success)]' : 'bg-[var(--color-error)]'}`}></span>
                {connected ? 'SSE CONNECTED' : 'DISCONNECTED'}
              </span>
            </div>
          </div>
        </div>

        <div className="flex items-center gap-4">
          <div className="hidden md:flex items-center gap-2 text-sm text-[var(--color-text-secondary)] bg-[var(--color-surface-hover)] px-3 py-1.5 rounded-full border border-[var(--color-border)]">
            <Network size={14} />
            <span>{activeClients} Observers</span>
          </div>
          <button 
            onClick={() => setIsDark(!isDark)}
            className="p-2.5 rounded-full bg-[var(--color-surface-hover)] border border-[var(--color-border)] text-[var(--color-text-secondary)] hover:text-[var(--color-text-primary)] transition-colors"
          >
            {isDark ? <Sun size={18} /> : <Moon size={18} />}
          </button>
        </div>
      </nav>

      <div className="max-w-7xl mx-auto grid grid-cols-1 lg:grid-cols-3 gap-6 h-[calc(100vh-140px)]">
        
        {/* Left Column: Node Topology */}
        <div className="lg:col-span-2 flex flex-col gap-6 overflow-y-auto custom-scrollbar pb-8">
          <div className="flex items-center justify-between px-2">
            <h2 className="text-lg font-semibold text-[var(--color-text-primary)] flex items-center gap-2">
              <Network size={18} className="text-[var(--color-brand-500)]" />
              Cluster Topology
            </h2>
            <span className="text-sm text-[var(--color-text-muted)] bg-[var(--color-surface-hover)] px-2 py-1 rounded-md border border-[var(--color-border)]">
              {nodes.length} Nodes Registered
            </span>
          </div>
          
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {nodes.length === 0 && (
              <div className="col-span-full py-12 text-center text-[var(--color-text-muted)] glass-card rounded-xl">
                <Activity className="mx-auto mb-3 opacity-50" size={32} />
                <p>Waiting for nodes to register with the coordinator...</p>
              </div>
            )}
            
            {nodes.sort((a,b) => a.id.localeCompare(b.id)).map(node => (
              <NodeCard 
                key={node.id} 
                node={node} 
                onKill={killNode} 
                onRestart={restartNode} 
              />
            ))}
          </div>
        </div>

        {/* Right Column: Terminal Stream */}
        <div className="lg:col-span-1 h-full max-h-[800px]">
          <TerminalLog events={events} />
        </div>
      </div>
    </div>
  );
};
