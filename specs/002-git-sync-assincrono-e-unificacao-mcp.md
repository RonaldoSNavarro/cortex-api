---
spec: 002
titulo: Bloqueio síncrono de git push nas escritas da wiki e divergência entre servidores MCP HTTP e Stdio
status: implementada
atualizado: 2026-10-17
---
# 002 — Bloqueio síncrono de git push nas escritas da wiki e divergência entre servidores MCP HTTP e Stdio

## Objetivo
Eliminar o bloqueio de rede do `git push` do caminho crítico das ferramentas de escrita na memória (`write_page`, `promote_rules`, `consolidate`) e unificar a configuração Jackson, os schemas e os handlers de ferramentas entre os servidores MCP HTTP (`cortex-api`) e Stdio (`cortex-mcp`).

## Contexto
Três problemas arquiteturais afetam a confiabilidade e a manutenção das ferramentas do Cortex:

1. **Bloqueio síncrono de 5s a 12s em `ProjectRepository.writePage()` (`cortex-core/src/main/java/com/cortex/core/ProjectRepository.java:276, 283-320`)**:
   Toda chamada a `writePage()` (acionada diretamente por `write_page` e indiretamente por `consolidate`) executa `syncToGit()` de forma síncrona na thread da requisição, disparando 4 subprocessos em série (`git add .`, `git commit`, `git branch -M main` e `git push -u origin main` via SSH).
   Evidência real extraída de `docker logs cortex-api`:
   ```
   2026-04-17T03:07:32.393Z >>> [POST] Received body: {"id":3,"method":"tools/call","params":{"name":"write_page"...}}
   2026-04-17T03:07:40.761Z >>> [MCP] Captured response for ID: 3
   ```
   Uma única chamada levou **8,37 segundos**. Quando o agente encadeia múltiplas chamadas ou a conexão SSH oscila, a requisição ultrapassa o limite de 10s do Codex (`rmcp`) ou de 15s do `AsyncContext`, falhando no cliente mesmo com o arquivo `.md` já salvo localmente. Além disso, chamadas concorrentes de escrita no mesmo projeto podem executar `git` em paralelo e falhar com `.git/index.lock`.

2. **Violação da Regra #1 do `AGENTS.md` no servidor Stdio (`cortex-mcp/src/main/java/com/cortex/mcp/CortexMcpServer.java:40`)**:
   `CortexMcpServer.main()` instancia `new StdioServerTransport(new ObjectMapper())` sem configurar `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` e sem corrigir a duplicação da propriedade `"type"` em `McpSchema.TextContent`. Clientes MCP que usam transporte `stdio` e enviam capabilities customizadas (`elicitation`, `extensions`) falham no handshake.

3. **Duplicação e divergência de contratos entre `CortexMcpServer.java` e `McpConfig.java`**:
   As 9 ferramentas MCP estão definidas manualmente em dois lugares distintos:
   - Em `CortexMcpServer.java:63`, o schema de `bootstrap` exige `"required": ["project", "root_path"]`.
   - Em `McpConfig.java:149`, o schema de `bootstrap` exige apenas `"required": ["root_path"]` e infere `project` a partir do nome do diretório (`McpConfig.java:293-305`).
   Qualquer evolução de ferramenta precisa ser editada em dobro e já apresenta deriva funcional.

## Comportamento esperado
- Quando `writePage()` é chamado em `ProjectRepository`, o arquivo Markdown é gravado no disco e indexado no `LexicalIndex` de forma síncrona e imediata, retornando o controle ao chamador sem aguardar subprocessos de rede (`git push`).
- A sincronização Git (`git add`, `git commit`, `git push`) passa a ser executada de forma assíncrona em background em Virtual Threads (Java 25), serializada por projeto (evitando concorrência de `.git/index.lock` no mesmo repositório) e com tempo limite (timeout) por subprocesso para nunca travar indefinidamente caso o SSH aguarde input ou perca conectividade.
- É possível desabilitar a sincronização Git automática por configuração (`cortex.git.sync-enabled=false` via propriedade Spring, System property ou setter) para execução determinística em testes automatizados e ambientes sem remoto Git.
- O módulo `cortex-mcp` (`CortexMcpTools`) centraliza a fábrica de `ObjectMapper` do MCP (com `FAIL_ON_UNKNOWN_PROPERTIES = false` e serialização limpa de `McpSchema.TextContent`) e o registro/definição das 9 ferramentas MCP (`capture`, `write_page`, `lint`, `promote_rules`, `query`, `consolidate`, `stats`, `bootstrap`, `handoff`), sendo reutilizado tanto pelo `CortexMcpServer` (Stdio) quanto pelo `McpConfig` (`cortex-api`).

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** Uma chamada a `write_page` (ou `ProjectRepository.writePage`) grava o arquivo `.md` em disco, atualiza o índice léxico para buscas imediatas via `query` e retorna em menos de 500ms, mesmo quando a sincronização Git subjacente demora mais de 2 segundos.
- **CA-2:** Cinco chamadas concorrentes a `writePage` para o mesmo projeto atualizam os respectivos arquivos `.md` sem erro de concorrência (`.git/index.lock`) e todas as tarefas de sincronização Git agendadas para o projeto executam de forma serializada.
- **CA-3:** Se o subprocesso Git em background falhar (ex.: repositório sem remoto, código de saída diferente de zero ou timeout de processo expirado), o arquivo `.md` escrito em disco e sua entrada no índice léxico permanecem intactos e nenhuma exceção não tratada derruba a chamada da ferramenta MCP.
- **CA-4:** Com a sincronização Git desabilitada por configuração (`cortex.git.sync-enabled=false`), `writePage` persiste o arquivo `.md` e atualiza o índice léxico sem invocar subprocessos `git`.
- **CA-5:** O `ObjectMapper` do MCP utilizado em `CortexMcpServer` (Stdio) ignora propriedades desconhecidas no payload de `initialize` (como `"elicitation":{"form":{}}`) sem lançar `UnrecognizedPropertyException` e serializa `McpSchema.TextContent` com apenas uma propriedade `"type":"text"`.
- **CA-6:** Tanto no servidor Stdio (`CortexMcpServer`) quanto no servidor HTTP (`McpConfig`), o schema da ferramenta `bootstrap` declara apenas `["root_path"]` como obrigatório (`required`), e uma chamada a `bootstrap` omitindo `project` (passando apenas `root_path: "/caminho/meu-projeto"`) infere `project = "meu-projeto"` nos dois servidores.
- **CA-7:** Sem nenhuma mudança nas chamadas aos endpoints REST de `CommandController` (`/projects/{project}/capture`, `/wiki`, `/lint`, `/promote-rules`, `/query`, `/consolidate`, `/stats`, `/bootstrap`, `/handoff`), todos continuam respondendo HTTP 200 com o mesmo contrato de resposta atual.

## Fora de escopo
- Correções de transporte HTTP/SSE (`AsyncContext`, `pendingResponses`, `server/discover` e `notifications/roots/list_changed`) — tratadas na **Spec 001**.
- Mapeamento de diretórios do host Windows/WSL no `docker-compose.yml` — tratado na **Spec 003**.
- Substituição da CLI `git` por biblioteca embarcada (como JGit) — fora de escopo para manter o princípio KISS do projeto (Regra 4 de `.agents/AGENTS.md`: zero dependências pesadas desnecessárias).

## Invariantes tocadas
- **Regra 1 (`AGENTS.md` — Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false`)**: Estendida para cobrir obrigatoriamente também o `CortexMcpServer` (Stdio), eliminando a violação existente em `CortexMcpServer.java:40`.
- **Princípios de Memória LLM Wiki (`.agents/AGENTS.md` — Regras 1 a 4)**: Preservados integralmente — o arquivo `.md` continua sendo a unidade atômica gravada imediatamente em disco e versionada via Git, sem introdução de bancos externos ou infraestrutura extra.

## Testes que cobrem esta spec
- `cortex-core/src/test/java/contract/Spec002GitSyncAssincronoTest.java` → CA-1, CA-2, CA-3, CA-4
- `cortex-api/src/test/java/contract/Spec002UnificacaoMcpTest.java` → CA-5, CA-6, CA-7

## Notas
Unificação realizada em `com.cortex.mcp.CortexMcpTools`.

