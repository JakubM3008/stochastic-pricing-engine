---
name: trading-ui-design
description: Guides quant trading UI construction using WebSockets, Web Workers, memoization, and paint optimizations.
---

# Performant Quant Trading UI Design Skill

## 🛠️ Execution Workflow (Step-by-Step Task Layout)
When prompted to build or refactor a layout component, the subagent must proceed sequentially through these three verification gates:

### Phase 1: Wire Contract Mapping
Document the exact mapping of Java types to TypeScript primitives.

```typescript
// Java Model: com.quant.dto.MarketDepthUpdate
// Wire Serialization: JSON
export interface MarketDepthRowDTO {
  price: string;    // Maps from Java BigDecimal to prevent floating point drift
  size: string;     // Maps from Java BigDecimal 
  orderCount: number; // Maps from Java long (safe up to 9,007,199,254,740,991)
}

export interface OrderBookPayloadDTO {
  symbol: string;
  sequenceId: string; // Maps from Java long (as string to prevent 64-bit precision truncation)
  bids: MarketDepthRowDTO[];
  asks: MarketDepthRowDTO[];
  timestamp: number;  // Epoch Milliseconds
}
```

### Phase 2: Reactive Streaming Core
Construct a clean subscription layer interfacing with Spring’s WebSocket messaging structure (@MessageMapping / @SendTo).

```typescript
import { Client, Message } from '@stomp/stompjs';

export const createWebSocketConfig = (symbol: string, onMessageReceived: (data: OrderBookPayloadDTO) => void) => {
  const client = new Client({
    brokerURL: 'ws://localhost:8080/ws-trading',
    connectHeaders: { login: 'quant_ui_client', passcode: 'trusted_token' },
    heartbeatIncoming: 4000,
    heartbeatOutgoing: 4000,
    reconnectDelay: 2000,
  });

  client.onConnect = (frame) => {
    client.subscribe(`/topic/marketdepth/${symbol}`, (message: Message) => {
      if (message.body) {
        const payload: OrderBookPayloadDTO = JSON.parse(message.body);
        onMessageReceived(payload);
      }
    });
  };

  return client;
};
```

### Phase 3: Hardware-Accelerated Memoized Interface
Render components using performance-optimized styles. Use CSS transforms (transform: translate3d) for flashes and price ticks to skip heavy browser paint cycles.

```typescript
import React, { useEffect, useState, useMemo } from 'react';

const OrderBookRowItem = React.memo(({ price, size, orderCount, type }: MarketDepthRowDTO & { type: 'bid' | 'ask' }) => {
  const textColor = type === 'bid' ? 'text-emerald-400' : 'text-rose-400';
  return (
    <div className="grid grid-cols-3 gap-4 px-3 py-0.5 font-mono text-xs hover:bg-slate-800/60 select-none will-change-transform">
      <span className={`${textColor} font-semibold`}>{Number(price).toFixed(2)}</span>
      <span className="text-right text-slate-200">{Number(size).toLocaleString(undefined, { minimumFractionDigits: 4 })}</span>
      <span className="text-right text-slate-500">{orderCount}</span>
    </div>
  );
});
OrderBookRowItem.displayName = 'OrderBookRowItem';
```

## 📈 Quality Assurance Checkpoints
Before delivering generated source files, ensure the answer verifies:

1. **Memory Leak Safeguards**: All WebSocket handlers include a robust cleanup function mapping to `useEffect` unmount stages.
2. **Primitive Bounds**: No truncation anomalies occur with highly precise IDs or sizes.
3. **Structural Isolation**: Renders are limited structurally to localized frames to avoid global layout computation traps.
