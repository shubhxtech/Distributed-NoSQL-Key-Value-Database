import React from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ClusterEvent } from '../hooks/useClusterStream';
import { Activity, Database, ServerCrash, Save, AlertCircle } from 'lucide-react';

interface TerminalLogProps {
  events: ClusterEvent[];
}

export const TerminalLog: React.FC<TerminalLogProps> = ({ events }) => {
  const getIcon = (type: string, success: boolean) => {
    switch (type) {
      case 'OPERATION': return success ? <Activity size={14} className="text-[var(--color-success)]" /> : <AlertCircle size={14} className="text-[var(--color-error)]" />;
      case 'MEMTABLE': return <Database size={14} className="text-[var(--color-info)]" />;
      case 'SST_FLUSH': return <Save size={14} className="text-[var(--color-brand-500)]" />;
      case 'NODE_STATUS': return <ServerCrash size={14} className="text-[var(--color-warning)]" />;
      default: return <Activity size={14} />;
    }
  };

  const formatMessage = (ev: ClusterEvent) => {
    switch (ev.type) {
      case 'OPERATION':
        return `${ev.op} '${ev.key}' -> ${ev.nodeId} (${ev.latencyMs.toFixed(1)}ms) ${ev.success ? 'OK' : 'FAIL'}`;
      case 'SST_FLUSH':
        return `Memtable flushed to SSTable on ${ev.nodeId}. Total SSTs: ${ev.extra.sstableCount}`;
      case 'NODE_STATUS':
        return `Node ${ev.nodeId} status changed to ${ev.extra.status} (${ev.extra.role})`;
      case 'MEMTABLE':
        return `Node ${ev.nodeId} memtable: ${ev.extra.fillPercent}% full`;
      default:
        return `Unknown event from ${ev.nodeId}`;
    }
  };

  return (
    <div className="glass-card rounded-xl p-4 h-full flex flex-col font-mono text-sm overflow-hidden border border-[var(--color-border)]">
      <div className="flex items-center justify-between mb-4 pb-2 border-b border-[var(--color-border)]">
        <h3 className="font-semibold text-[var(--color-text-primary)] flex items-center gap-2">
          <Activity size={16} className="text-[var(--color-brand-500)]"/> 
          Cluster Activity Stream
        </h3>
        <span className="text-[var(--color-text-muted)] text-xs">Live</span>
      </div>
      
      <div className="flex-1 overflow-y-auto space-y-2 pr-2 custom-scrollbar">
        <AnimatePresence initial={false}>
          {events.filter(e => e.type !== 'MEMTABLE').map((ev) => (
            <motion.div
              key={ev.id}
              initial={{ opacity: 0, x: -10, height: 0 }}
              animate={{ opacity: 1, x: 0, height: 'auto' }}
              className="flex items-start gap-3 py-1 text-[var(--color-text-secondary)]"
            >
              <div className="mt-0.5">{getIcon(ev.type, ev.success)}</div>
              <div className="flex-1">
                <span className="text-[var(--color-text-muted)] text-xs mr-2">
                  {new Date(ev.timestampMs || Date.now()).toISOString().split('T')[1].slice(0,-1)}
                </span>
                <span className={ev.type === 'OPERATION' && !ev.success ? 'text-[var(--color-error)]' : ''}>
                  {formatMessage(ev)}
                </span>
              </div>
            </motion.div>
          ))}
        </AnimatePresence>
        
        {events.length === 0 && (
          <div className="text-[var(--color-text-muted)] italic text-center mt-10">
            Waiting for cluster events...
          </div>
        )}
      </div>
    </div>
  );
};
