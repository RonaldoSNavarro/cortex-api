import asyncio
import json
import subprocess
import os

async def main():
    server_jar = "cortex-mcp/target/cortex-mcp-1.0-SNAPSHOT-shaded.jar"
    
    # Start the MCP server process
    process = await asyncio.create_subprocess_exec(
        "java", "-jar", server_jar,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE
    )

    # Helper to send a JSON-RPC request and get the response
    async def send_request(method, params=None):
        request = {
            "jsonrpc": "2.0",
            "id": 1,
            "method": method,
            "params": params or {}
        }
        process.stdin.write((json.dumps(request) + "\n").encode())
        await process.stdin.drain()
        
        response_line = await process.stdout.readline()
        if not response_line:
            return None
        return json.loads(response_line.decode())

    # Initialize MCP
    print("Initializing...")
    await send_request("initialize", {
        "protocolVersion": "2024-11-05",
        "capabilities": {},
        "clientInfo": {"name": "test-client", "version": "1.0.0"}
    })
    process.stdin.write((json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n").encode())
    await process.stdin.drain()

    # Call write_page tool
    print("Calling write_page...")
    response = await send_request("tools/call", {
        "name": "write_page",
        "arguments": {
            "project": "cortex-self",
            "type": "fact",
            "content": "# Teste de Consolidação\nEste é um conhecimento curado.",
            "tags": ["test", "consolidation"]
        }
    })
    print(json.dumps(response, indent=2))
    
    id_str = response["result"]["content"][0]["text"].split("ID: ")[1]
    
    # Call write_page again with supersedes
    print(f"\nCalling write_page with supersedes: {id_str}...")
    response2 = await send_request("tools/call", {
        "name": "write_page",
        "arguments": {
            "project": "cortex-self",
            "type": "fact",
            "content": "# Teste Evoluido\nNovo conhecimento.",
            "tags": ["test"],
            "supersedes": id_str
        }
    })
    print(json.dumps(response2, indent=2))

    process.terminate()

if __name__ == "__main__":
    asyncio.run(main())
