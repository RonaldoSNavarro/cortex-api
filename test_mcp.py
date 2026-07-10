import json
import subprocess
import sys

def send_request(proc, req):
    req_str = json.dumps(req) + "\n"
    proc.stdin.write(req_str)
    proc.stdin.flush()
    return json.loads(proc.stdout.readline())

def main():
    content = "Sistema Cortex reiniciado. Servidor MCP está vivo e respondendo perfeitamente aos testes locais!"
    
    proc = subprocess.Popen(
        ["java", "-jar", "f:/Dev/Projetos/cortex/cortex-mcp/target/cortex-mcp-1.0-SNAPSHOT.jar"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=sys.stderr,
        text=True
    )

    init_req = {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "antigravity-mcp-client", "version": "1.0.0"}
        }
    }
    send_request(proc, init_req)
    proc.stdin.write(json.dumps({"jsonrpc": "2.0", "method": "notifications/initialized"}) + "\n")
    proc.stdin.flush()

    tool_req = {
        "jsonrpc": "2.0",
        "id": 2,
        "method": "tools/call",
        "params": {
            "name": "capture",
            "arguments": {
                "project": "cortex-self",
                "type": "fact",
                "content": content
            }
        }
    }
    
    print(f"Enviando via MCP: {content}")
    resp = send_request(proc, tool_req)
    print(f"Resposta do Servidor MCP: {json.dumps(resp, ensure_ascii=False)}")

    proc.terminate()

if __name__ == "__main__":
    main()
