import { useState, useEffect, useCallback } from 'react';

export interface NodeState {
  id: string;
  host: string;
  grpcPort: number;
  httpPort: number;
  status: 'UP' | 'DOWN' | 'KILLED';
  role: string;
  blacklisted: boolean;
  memtableFillPercent: number;
  walSizeBytes: number;
  sstableCount: number;
}

export interface ClusterEvent {
  id: string; // generated client-side for keys
  type: string;
  nodeId: string;
  op: string;
  key: string;
  latencyMs: number;
  success: boolean;
  extra: Record<string, any>;
  timestampMs: number;
}

export function useClusterStream(coordinatorUrl: string = 'http://localhost:8080') {
  const [nodes, setNodes] = useState<Record<string, NodeState>>({});
  const [events, setEvents] = useState<ClusterEvent[]>([]);
  const [connected, setConnected] = useState(false);
  const [activeClients, setActiveClients] = useState(0);

  // Fetch initial state
  const fetchState = useCallback(async () => {
    try {
      const res = await fetch(`${coordinatorUrl}/api/v1/monitor/state`);
      const data = await res.json();
      const nodeMap: Record<string, NodeState> = {};
      
      data.nodes.forEach((n: any) => {
        nodeMap[n.id] = {
          ...n,
          status: n.blacklisted ? 'KILLED' : 'DOWN', // Will be updated by SSE
          role: 'FOLLOWER',
          memtableFillPercent: 0,
          walSizeBytes: 0,
          sstableCount: 0
        };
      });
      
      setNodes(nodeMap);
      setActiveClients(data.activeConnections);
    } catch (e) {
      console.error("Failed to fetch initial cluster state", e);
    }
  }, [coordinatorUrl]);

  useEffect(() => {
    fetchState();
  }, [fetchState]);

  // Connect SSE
  useEffect(() => {
    const sse = new EventSource(`${coordinatorUrl}/api/v1/monitor/events`);
    
    sse.onopen = () => setConnected(true);
    sse.onerror = () => setConnected(false);
    
    sse.addEventListener('cluster-event', (e) => {
      const eventData = JSON.parse(e.data);
      eventData.id = crypto.randomUUID();
      
      setEvents(prev => [eventData, ...prev].slice(0, 50)); // keep last 50 events
      
      setNodes(prev => {
        const current = { ...prev };
        const nodeId = eventData.nodeId;
        
        if (!current[nodeId]) {
          // If we see an unknown node, just return current state
          return current;
        }

        const node = { ...current[nodeId] };

        switch (eventData.type) {
          case 'NODE_STATUS':
            node.status = eventData.extra.status;
            node.role = eventData.extra.role;
            if (node.status === 'KILLED') node.blacklisted = true;
            else if (node.status === 'UP') node.blacklisted = false;
            break;
          case 'MEMTABLE':
            node.memtableFillPercent = eventData.extra.fillPercent;
            node.walSizeBytes = eventData.extra.walSizeBytes;
            node.sstableCount = eventData.extra.sstableCount;
            break;
          case 'SST_FLUSH':
            node.sstableCount = eventData.extra.sstableCount;
            node.memtableFillPercent = 0; // resets after flush
            break;
        }
        
        current[nodeId] = node;
        return current;
      });
    });

    return () => {
      sse.close();
    };
  }, [coordinatorUrl]);

  const killNode = async (nodeId: string) => {
    await fetch(`${coordinatorUrl}/api/v1/monitor/nodes/${nodeId}/kill`, { method: 'POST' });
  };

  const restartNode = async (nodeId: string) => {
    await fetch(`${coordinatorUrl}/api/v1/monitor/nodes/${nodeId}/restart`, { method: 'POST' });
  };

  return {
    nodes: Object.values(nodes),
    events,
    connected,
    activeClients,
    killNode,
    restartNode
  };
}
