import React, { useEffect, useRef, useState } from 'react';
import { motion, AnimatePresence } from 'framer-motion';
import type { ClusterEvent, NodeState } from '../hooks/useClusterStream';

interface WritePathProps {
  events: ClusterEvent[];
  nodes: NodeState[];
}

/** Animated SVG diagram of the write path: Client → Coordinator → Node → WAL → Memtable → SSTable */
export const WritePath: React.FC<WritePathProps> = ({ events, nodes }) => {
  const [activePacket, setActivePacket] = useState<{
    nodeId: string; op: string; key: string; success: boolean; id: string
  } | null>(null);
  const [flushNode, setFlushNode] = useState<string | null>(null);
  const prevEventId = useRef<string>('');

  // React to new events
  useEffect(() => {
    if (events.length === 0) return;
    const latest = events[0];
    if (latest.id === prevEventId.current) return;
    prevEventId.current = latest.id;

    if (latest.type === 'OPERATION') {
      setActivePacket({ nodeId: latest.nodeId, op: latest.op, key: latest.key, success: latest.success, id: latest.id });
      setTimeout(() => setActivePacket(null), 2000);
    }
    if (latest.type === 'SST_FLUSH') {
      setFlushNode(latest.nodeId);
      setTimeout(() => setFlushNode(null), 2000);
    }
  }, [events]);

  const nodeColors: Record<string, string> = {
    'node-1': '#14b8a6',
    'node-2': '#60a5fa',
    'node-3': '#a78bfa',
  };

  const opColor = activePacket?.op === 'PUT'
    ? '#10b981'
    : activePacket?.op === 'DELETE'
    ? '#f87171'
    : '#60a5fa';

  return (
    <div className="glass-card rounded-2xl border border-[var(--color-border)] p-4 overflow-hidden">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-[var(--color-text-primary)]">Write Path Visualizer</h3>
        <span className="text-[10px] font-mono uppercase tracking-widest text-[var(--color-text-muted)] bg-[var(--color-surface-hover)] px-2 py-0.5 rounded-full border border-[var(--color-border)]">
          WAL → Memtable → SSTable
        </span>
      </div>

      <svg viewBox="0 0 760 160" className="w-full" style={{ height: 160 }}>
        {/* ── Nodes across bottom ── */}
        {/* Client */}
        <g>
          <rect x="10" y="60" width="90" height="40" rx="8"
            fill="var(--glass-bg)" stroke="var(--color-border)" strokeWidth="1.5"/>
          <text x="55" y="78" textAnchor="middle" fontSize="10" fill="var(--color-text-muted)" fontFamily="monospace">CLIENT</text>
          <text x="55" y="93" textAnchor="middle" fontSize="9" fill="var(--color-text-secondary)" fontFamily="monospace">REST</text>
        </g>

        {/* Arrow: Client → Coordinator */}
        <line x1="100" y1="80" x2="165" y2="80" stroke="var(--color-border)" strokeWidth="1.5" strokeDasharray="4 3"/>
        <AnimatePresence>
          {activePacket && (
            <motion.circle
              key={activePacket.id + '-cto'}
              cx={0} cy={80} r={5} fill={opColor}
              initial={{ cx: 104 }} animate={{ cx: 163 }}
              transition={{ duration: 0.4, ease: 'easeOut' }}
              style={{ filter: `drop-shadow(0 0 4px ${opColor})` }}
            />
          )}
        </AnimatePresence>

        {/* Coordinator */}
        <g>
          <rect x="166" y="56" width="110" height="48" rx="8"
            fill="var(--glass-bg)" stroke="var(--color-brand-500)" strokeWidth="1.5"/>
          <text x="221" y="76" textAnchor="middle" fontSize="10" fill="var(--color-brand-500)" fontFamily="monospace" fontWeight="bold">COORDINATOR</text>
          <text x="221" y="92" textAnchor="middle" fontSize="8.5" fill="var(--color-text-muted)" fontFamily="monospace">Round-Robin Router</text>
          <AnimatePresence>
            {activePacket && (
              <motion.rect
                key={activePacket.id + '-coord'}
                x="166" y="56" width="110" height="48" rx="8"
                fill="transparent"
                stroke={opColor}
                strokeWidth="2"
                initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 1, 0] }}
                transition={{ duration: 0.8, times: [0, 0.1, 0.7, 1] }}
              />
            )}
          </AnimatePresence>
        </g>

        {/* Arrows: Coordinator → Nodes */}
        {/* node-1 */}
        <line x1="276" y1="70" x2="355" y2="38" stroke="var(--color-border)" strokeWidth="1" strokeDasharray="4 3"/>
        {/* node-2 */}
        <line x1="276" y1="80" x2="355" y2="80" stroke="var(--color-border)" strokeWidth="1" strokeDasharray="4 3"/>
        {/* node-3 */}
        <line x1="276" y1="90" x2="355" y2="122" stroke="var(--color-border)" strokeWidth="1" strokeDasharray="4 3"/>

        {/* Animated packet to target node */}
        <AnimatePresence>
          {activePacket && (() => {
            const yMap: Record<string, { y1: number; y2: number }> = {
              'node-1': { y1: 70, y2: 38 },
              'node-2': { y1: 80, y2: 80 },
              'node-3': { y1: 90, y2: 122 },
            };
            const pos = yMap[activePacket.nodeId] ?? yMap['node-2'];
            return (
              <motion.circle
                key={activePacket.id + '-node'}
                r={5} fill={opColor}
                initial={{ cx: 278, cy: pos.y1 }}
                animate={{ cx: 353, cy: pos.y2 }}
                transition={{ duration: 0.4, ease: 'easeOut', delay: 0.35 }}
                style={{ filter: `drop-shadow(0 0 5px ${opColor})` }}
              />
            );
          })()}
        </AnimatePresence>

        {/* 3 Node boxes */}
        {['node-1', 'node-2', 'node-3'].map((nid, i) => {
          const cy = [28, 70, 112][i];
          const color = nodeColors[nid];
          const isActive = activePacket?.nodeId === nid;
          const nodeState = nodes.find(n => n.id === nid);
          const fillPct = nodeState?.memtableFillPercent ?? 0;

          return (
            <g key={nid}>
              <rect x="356" y={cy} width="90" height="40" rx="6"
                fill="var(--glass-bg)"
                stroke={isActive ? opColor : color}
                strokeWidth={isActive ? 2 : 1}
              />
              <AnimatePresence>
                {isActive && (
                  <motion.rect key={nid + '-glow'} x="356" y={cy} width="90" height="40" rx="6"
                    fill="transparent" stroke={opColor} strokeWidth="2"
                    initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                    transition={{ duration: 0.8 }}
                  />
                )}
              </AnimatePresence>
              <text x="401" y={cy + 14} textAnchor="middle" fontSize="9.5" fill={color} fontFamily="monospace" fontWeight="bold">
                {nid.toUpperCase()}
              </text>
              {/* Memtable fill mini bar */}
              <rect x="362" y={cy + 20} width="78" height="8" rx="3" fill="var(--color-surface-hover)"/>
              <motion.rect
                x="362" y={cy + 20}
                width={0} height="8" rx="3"
                animate={{ width: Math.max(4, 0.78 * fillPct) }}
                transition={{ duration: 0.5 }}
                fill={fillPct > 80 ? '#f59e0b' : color}
              />
              <text x="401" y={cy + 26} textAnchor="middle" fontSize="7" fill="var(--color-text-muted)" fontFamily="monospace">
                mem {fillPct}%
              </text>
            </g>
          );
        })}

        {/* Arrow: Node → WAL/SSTable pipeline */}
        <line x1="446" y1="80" x2="510" y2="80" stroke="var(--color-border)" strokeWidth="1" strokeDasharray="4 3"/>

        {/* WAL box */}
        <g>
          <rect x="511" y="60" width="70" height="40" rx="6"
            fill="var(--glass-bg)" stroke="var(--color-warning)" strokeWidth="1.5"/>
          <text x="546" y="78" textAnchor="middle" fontSize="10" fill="var(--color-warning)" fontFamily="monospace" fontWeight="bold">WAL</text>
          <text x="546" y="92" textAnchor="middle" fontSize="8" fill="var(--color-text-muted)" fontFamily="monospace">append+sync</text>
          <AnimatePresence>
            {activePacket?.op === 'PUT' && (
              <motion.rect key={'wal-' + activePacket.id} x="511" y="60" width="70" height="40" rx="6"
                fill="rgba(245,158,11,0.1)" stroke="#f59e0b" strokeWidth="2"
                initial={{ opacity: 0 }} animate={{ opacity: [0, 1, 0] }}
                transition={{ duration: 0.8, delay: 0.7 }}
              />
            )}
          </AnimatePresence>
        </g>

        {/* Arrow WAL → SkipList */}
        <line x1="581" y1="80" x2="620" y2="80" stroke="var(--color-border)" strokeWidth="1" strokeDasharray="3 2"/>

        {/* Memtable box */}
        <g>
          <rect x="621" y="60" width="72" height="40" rx="6"
            fill="var(--glass-bg)" stroke="var(--color-info)" strokeWidth="1.5"/>
          <text x="657" y="78" textAnchor="middle" fontSize="10" fill="var(--color-info)" fontFamily="monospace" fontWeight="bold">SkipList</text>
          <text x="657" y="92" textAnchor="middle" fontSize="8" fill="var(--color-text-muted)" fontFamily="monospace">in-memory</text>
        </g>

        {/* Flush arrow (downward) */}
        <AnimatePresence>
          {flushNode && (
            <motion.g key={'flush-arrow'} initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }}>
              <line x1="657" y1="100" x2="657" y2="130" stroke="#14b8a6" strokeWidth="2"/>
              <polygon points="653,126 657,136 661,126" fill="#14b8a6"/>
              <rect x="619" y="136" width="76" height="20" rx="5" fill="rgba(20,184,166,0.15)" stroke="#14b8a6" strokeWidth="1.5"/>
              <text x="657" y="150" textAnchor="middle" fontSize="9" fill="#14b8a6" fontFamily="monospace">→ SSTable</text>
            </motion.g>
          )}
        </AnimatePresence>

        {/* Op label */}
        <AnimatePresence>
          {activePacket && (
            <motion.text
              key={activePacket.id + '-label'}
              x="380" y="16"
              textAnchor="middle"
              fontSize="10"
              fontFamily="monospace"
              fontWeight="bold"
              fill={opColor}
              initial={{ opacity: 0, y: 20 }}
              animate={{ opacity: 1, y: 16 }}
              exit={{ opacity: 0 }}
            >
              {activePacket.op} '{activePacket.key.length > 12 ? activePacket.key.slice(0, 12) + '…' : activePacket.key}' → {activePacket.nodeId}
            </motion.text>
          )}
        </AnimatePresence>
      </svg>
    </div>
  );
};
