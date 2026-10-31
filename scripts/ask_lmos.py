import asyncio
import json
import sys
import uuid

import websockets

URL = "ws://127.0.0.1:18081/subscriptions"

QUERY = """
subscription($request: AgentRequestInput!) {
  agent(request: $request) {
    status
    type
    responseTime
    messages {
      role
      content
    }
  }
}
"""

HEADERS = {
    "x-tenant-id": "default",
    "x-subset": "stable",
}

async def main():
    question = " ".join(sys.argv[1:]).strip()
    if not question:
        raise SystemExit(
            'Usage: python lmos_test.py "Your question"'
        )

    conversation_id = "routing-test-" + uuid.uuid4().hex[:12]
    print("conversationId:", conversation_id, flush=True)
    print("question:", question, flush=True)

    request = {
        "conversationContext": {
            "conversationId": conversation_id,
            "turnId": "1",
        },
        "userContext": {
            "userId": "tester",
            "profile": [],
        },
        "systemContext": [
            {"key": "channelId", "value": "web"},
            {"key": "tenantId", "value": "default"},
        ],
        "messages": [
            {
                "role": "user",
                "format": "text",
                "content": question,
            }
        ],
    }

    async with websockets.connect(
        URL,
        subprotocols=["graphql-transport-ws"],
        proxy=None,
        additional_headers=HEADERS,
    ) as ws:
        await ws.send(json.dumps({
            "type": "connection_init",
            "payload": HEADERS,
        }))

        while True:
            msg = json.loads(
                await asyncio.wait_for(ws.recv(), timeout=15)
            )
            print("<-", json.dumps(msg, ensure_ascii=False), flush=True)

            if msg.get("type") == "connection_ack":
                break
            if msg.get("type") == "ping":
                await ws.send(json.dumps({"type": "pong"}))
            else:
                raise RuntimeError(
                    "Unexpected message before connection_ack"
                )

        await ws.send(json.dumps({
            "id": "1",
            "type": "subscribe",
            "payload": {
                "query": QUERY,
                "variables": {"request": request},
            },
        }))

        while True:
            msg = json.loads(
                await asyncio.wait_for(ws.recv(), timeout=360)
            )
            print("<-", json.dumps(msg, ensure_ascii=False), flush=True)

            if msg.get("type") == "ping":
                await ws.send(json.dumps({"type": "pong"}))
            elif msg.get("type") in ("complete", "error"):
                break

asyncio.run(main())
