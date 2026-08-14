import React, { useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { NodeState } from '../hooks/useClusterStream';
import { Server, Database, Save, HardDrive, Play, Square, Layers, Loader2 } from 'lucide-react';
import { PieChart, Pie, Cell } from 'recharts';

interface NodeCardProps {
  node: NodeState;
  onKill: (id: string) => void;
  onRestart: (id: string) => void;
  onCompact: (id: string, httpPort: number) => void;
}

export const NodeCard: React.FC<NodeCardProps> = ({ node, onKill, onRestart, onCompact }) => {
  const isUp = node.status === 'UP';
  const isKilled = node.status === 'KILLED' || node.blacklisted;
  const [compacting, setCompacting] = useState(false);

  const statusColor = isUp ? 'var(--color-success)' : (isKilled ? 'var(--color-error)' : 'var(--color-warning)');
  const statusBg    = isUp ? 'bg-emerald-500/10' : (isKilled ? 'bg-red-500/10' : 'bg-yellow-500/10');

  // Format bytes → human readable
  const formatSize = (bytes: number) => {
    if (!bytes || bytes < 0) return '—';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
  };

  const handleCompact = async () => {
    setCompacting(true);
    await onCompact(node.id, node.httpPort);
    setTimeout(() => setCompacting(false), 3000); // visual feedback for 3s
  };

  // Memtable radial chart data
  const memtableData = [
    { name: 'Filled', value: node.memtableFillPercent },
    { name: 'Empty', value: Math.max(0, 100 - node.memtableFillPercent) }
  ];

  // SSTable bar: show up to 8 "slots" visually
  const MAX_SST_VISUAL = 8;
  const sstSlots = Array.from({ length: MAX_SST_VISUAL }, (_, i) => i < node.sstableCount);
  const sstBarColor = node.sstableCount >= 4
    ? 'var(--color-warning)'
    : 'var(--color-brand-500)';

  return (
    <motion.div
      layout
      initial={{ opacity: 0, scale: 0.95 }}
      animate={{ opacity: 1, scale: 1 }}
      className={`glass-card rounded-2xl overflow-hidden border ${isKilled ? 'opacity-70 grayscale-[30%]' : ''}`}
      style={{ borderColor: isUp ? 'var(--color-border)' : statusColor }}
    >
      {/* Header */}
      <div className={`p-4 flex items-center justify-between border-b border-[var(--color-border)] ${statusBg}`}>
        <div className="flex items-center gap-3">
          <div className="relative">
            <Server size={20} style={{ color: statusColor }} />
            {isUp && (
              <span className="absolute -top-1 -right-1 flex h-2.5 w-2.5">
                <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                <span className="relative inline-flex rounded-full h-2.5 w-2.5 bg-emerald-500"></span>
              </span>
            )}
          </div>
          <div>
            <h3 className="font-bold text-[var(--color-text-primary)] leading-tight">{node.id}</h3>
            <span className="text-xs text-[var(--color-text-muted)] font-mono">{node.host}:{node.grpcPort}</span>
          </div>
        </div>

        <div className="flex flex-col items-end gap-1">
          <span className="px-2.5 py-0.5 rounded-full text-xs font-medium border"
                style={{ color: statusColor, borderColor: statusColor, backgroundColor: 'transparent' }}>
            {isKilled ? 'PARTITIONED' : node.status}
          </span>
          <span className="text-[10px] uppercase tracking-wider font-semibold text-[var(--color-text-muted)]">
            {node.role}
          </span>
        </div>
      </div>

      {/* Metrics Grid */}
      <div className="p-4 grid grid-cols-2 gap-3">

        {/* Memtable radial */}
        <div className="col-span-2 sm:col-span-1 bg-[var(--color-surface-hover)] rounded-xl p-3 flex items-center gap-3">
          <div className="w-12 h-12 relative flex-shrink-0">
            <PieChart width={48} height={48}>
              <Pie data={memtableData} cx="50%" cy="50%"
                   innerRadius={16} outerRadius={24}
                   startAngle={90} endAngle={-270} dataKey="value" stroke="none">
                <Cell key="filled" fill={node.memtableFillPercent > 80 ? 'var(--color-warning)' : 'var(--color-brand-500)'} />
                <Cell key="empty"  fill="var(--color-border)" />
              </Pie>
            </PieChart>
            <div className="absolute inset-0 flex items-center justify-center">
              <span className="text-[10px] font-bold text-[var(--color-text-primary)]">{node.memtableFillPercent}%</span>
            </div>
          </div>
          <div>
            <div className="text-xs text-[var(--color-text-muted)] flex items-center gap-1 mb-0.5">
              <Database size={12}/> Memtable
            </div>
            <div className="text-sm font-semibold text-[var(--color-text-primary)]">SkipList</div>
            <div className="text-[10px] text-[var(--color-text-muted)]">{formatSize(node.walSizeBytes)} WAL</div>
          </div>
        </div>

        {/* WAL Size */}
        <div className="bg-[var(--color-surface-hover)] rounded-xl p-3 flex flex-col justify-center">
          <div className="text-xs text-[var(--color-text-muted)] flex items-center gap-1 mb-1">
            <HardDrive size={12}/> WAL Log
          </div>
          <div className="text-sm font-bold text-[var(--color-text-primary)] font-mono">
            {formatSize(node.walSizeBytes)}
          </div>
        </div>

        {/* Cache Hit % */}
        <div className="bg-[var(--color-surface-hover)] rounded-xl p-3 flex flex-col justify-center">
          <div className="text-xs text-[var(--color-text-muted)] flex items-center gap-1 mb-1">
            <Database size={12}/> LRU Cache
          </div>
          <div className="flex items-end gap-1">
            <span className="text-lg font-bold leading-none" style={{
              color: node.cacheHitPercent > 60 ? 'var(--color-success)'
                   : node.cacheHitPercent > 20 ? 'var(--color-warning)'
                   : 'var(--color-text-muted)'
            }}>
              {node.cacheHitPercent}%
            </span>
            <span className="text-[10px] text-[var(--color-text-muted)] mb-0.5">hit</span>
          </div>
          <div className="mt-1 h-1 rounded-full bg-[var(--color-border)] overflow-hidden">
            <motion.div
              className="h-full rounded-full"
              animate={{ width: `${node.cacheHitPercent}%` }}
              transition={{ duration: 0.5 }}
              style={{ background: 'linear-gradient(to right, var(--color-brand-500), var(--color-info))' }}
            />
          </div>
        </div>

        {/* SSTables with bar visualization */}
        <div className="col-span-2 bg-[var(--color-surface-hover)] rounded-xl p-3">
          <div className="flex items-center justify-between mb-2">
            <div className="text-xs text-[var(--color-text-muted)] flex items-center gap-1">
              <Layers size={12}/> SSTables on Disk
            </div>
            <span className="text-xs font-bold" style={{ color: sstBarColor }}>
              {node.sstableCount} files
            </span>
          </div>
          {/* Visual SSTable slots */}
          <div className="flex gap-1 items-end h-4">
            {sstSlots.map((filled, i) => (
              <motion.div
                key={i}
                initial={false}
                animate={{ scaleY: filled ? 1 : 0.3, opacity: filled ? 1 : 0.2 }}
                transition={{ duration: 0.3, delay: i * 0.04 }}
                className="flex-1 rounded-sm origin-bottom"
                style={{
                  height: '100%',
                  backgroundColor: filled ? sstBarColor : 'var(--color-border)',
                }}
              />
            ))}
          </div>
          {node.sstableCount >= 4 && (
            <p className="text-[10px] text-[var(--color-warning)] mt-1.5">
              ⚠ Compaction recommended (≥4 files)
            </p>
          )}
        </div>
      </div>

      {/* Controls */}
      <div className="px-4 pb-4 pt-2 border-t border-[var(--color-border)] flex gap-2">
        {!isKilled ? (
          <button
            onClick={() => onKill(node.id)}
            className="flex-1 flex items-center justify-center gap-2 py-2 rounded-lg text-xs font-semibold text-white bg-[var(--color-error)] hover:opacity-90 transition-opacity"
          >
            <Square size={14} fill="currentColor" /> Isolate Node
          </button>
        ) : (
          <button
            onClick={() => onRestart(node.id)}
            className="flex-1 flex items-center justify-center gap-2 py-2 rounded-lg text-xs font-semibold text-white bg-[var(--color-success)] hover:opacity-90 transition-opacity"
          >
            <Play size={14} fill="currentColor" /> Heal Partition
          </button>
        )}

        {/* Compact button — only when node is UP */}
        <AnimatePresence>
          {isUp && (
            <motion.button
              initial={{ opacity: 0, width: 0 }}
              animate={{ opacity: 1, width: 'auto' }}
              exit={{ opacity: 0, width: 0 }}
              onClick={handleCompact}
              disabled={compacting}
              className="flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-semibold border border-[var(--color-brand-500)] text-[var(--color-brand-500)] hover:bg-[var(--color-brand-500)] hover:text-white transition-all disabled:opacity-60 whitespace-nowrap"
            >
              {compacting
                ? <><Loader2 size={13} className="animate-spin" /> Running…</>
                : <><Save size={13} /> Compact</>
              }
            </motion.button>
          )}
        </AnimatePresence>
      </div>
    </motion.div>
  );
};
