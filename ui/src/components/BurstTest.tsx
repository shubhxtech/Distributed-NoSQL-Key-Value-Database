import React, { useState } from 'react';
import { motion } from 'framer-motion';
import { Zap, RefreshCw } from 'lucide-react';

interface BurstTestProps {
  coordinatorUrl?: string;
}

export const BurstTest: React.FC<BurstTestProps> = ({
  coordinatorUrl = 'http://localhost:8080',
}) => {
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState(0);
  const [total, setTotal] = useState(50);
  const [delay, setDelay] = useState(80);
  const [done, setDone] = useState(false);
  const [results, setResults] = useState<{ ok: number; fail: number }>({ ok: 0, fail: 0 });

  const fire = async () => {
    setRunning(true);
    setDone(false);
    setProgress(0);
    setResults({ ok: 0, fail: 0 });

    let ok = 0, fail = 0;

    for (let i = 1; i <= total; i++) {
      const key = `burst-key-${Date.now()}-${i}`;
      const value = `burst-value-${i}`;
      try {
        const res = await fetch(`${coordinatorUrl}/api/v1/kv/${key}`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ value, ttlMs: 0 }),
        });
        if (res.ok) ok++; else fail++;
      } catch {
        fail++;
      }
      setProgress(i);
      setResults({ ok, fail });
      if (delay > 0) await new Promise(r => setTimeout(r, delay));
    }

    setRunning(false);
    setDone(true);
  };

  const pct = total > 0 ? (progress / total) * 100 : 0;

  return (
    <div className="glass-card rounded-2xl border border-[var(--color-border)] p-4">
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-semibold text-[var(--color-text-primary)] flex items-center gap-2">
          <Zap size={15} className="text-[var(--color-warning)]" />
          Burst Write Test
        </h3>
        {done && (
          <span className="text-xs text-[var(--color-success)] font-mono">
            ✓ {results.ok} ok · {results.fail} fail
          </span>
        )}
      </div>

      <div className="flex flex-wrap gap-3 mb-4">
        <label className="flex flex-col gap-1 text-[10px] text-[var(--color-text-muted)] uppercase tracking-wide">
          Writes
          <input
            type="number" value={total} min={5} max={500}
            onChange={e => setTotal(Number(e.target.value))}
            disabled={running}
            className="w-20 px-2 py-1 rounded-lg bg-[var(--color-surface-hover)] border border-[var(--color-border)] text-[var(--color-text-primary)] text-sm font-mono focus:outline-none focus:border-[var(--color-brand-500)]"
          />
        </label>
        <label className="flex flex-col gap-1 text-[10px] text-[var(--color-text-muted)] uppercase tracking-wide">
          Delay (ms)
          <input
            type="number" value={delay} min={0} max={500}
            onChange={e => setDelay(Number(e.target.value))}
            disabled={running}
            className="w-24 px-2 py-1 rounded-lg bg-[var(--color-surface-hover)] border border-[var(--color-border)] text-[var(--color-text-primary)] text-sm font-mono focus:outline-none focus:border-[var(--color-brand-500)]"
          />
        </label>
      </div>

      {/* Progress bar */}
      <div className="h-2 rounded-full bg-[var(--color-surface-hover)] border border-[var(--color-border)] mb-3 overflow-hidden">
        <motion.div
          className="h-full rounded-full"
          style={{ background: 'linear-gradient(to right, var(--color-brand-500), var(--color-info))' }}
          animate={{ width: `${pct}%` }}
          transition={{ duration: 0.1 }}
        />
      </div>
      <div className="flex items-center justify-between text-[10px] text-[var(--color-text-muted)] font-mono mb-3">
        <span>{progress}/{total} sent</span>
        {running && <span className="text-[var(--color-brand-500)] animate-pulse">▶ running…</span>}
      </div>

      <button
        onClick={fire}
        disabled={running}
        className="w-full flex items-center justify-center gap-2 py-2 rounded-xl text-sm font-bold text-white transition-all disabled:opacity-50"
        style={{ background: 'linear-gradient(135deg, var(--color-brand-500), var(--color-info))' }}
      >
        {running
          ? <><RefreshCw size={15} className="animate-spin" /> Firing…</>
          : <><Zap size={15} /> Fire {total} Writes</>
        }
      </button>
    </div>
  );
};
