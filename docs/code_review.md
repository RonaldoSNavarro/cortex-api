# Code Review Completo do Sistema Cortex

Este documento consolida a revisão técnica de ponta a ponta de todos os módulos do repositório **Cortex** (`cortex-core`, `cortex-mcp`, `cortex-api`, `cortex-cli`, `Dockerfile`, `docker-compose.yml` e `pom.xml`), avaliando concorrência, segurança, integridade de dados, aderência às regras de negócio ([AGENTS.md](file:///mnt/g/Dev/Projetos/cortex/AGENTS.md)) e às skills `java-25`, `spring-boot`, `docker`, `java-springboot`, `java-junit`, `java-docs` e `spec-driven-development`.

---

## ✅ Pontos Fortes Atuais (Pós-Specs 001, 002 e 003)

1. **Transporte MCP HTTP/SSE Estável ([McpConfig.java](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/config/McpConfig.java))**:
   - Leitura síncrona do body na thread do Servlet antes de `req.startAsync()`, evitando condições de corrida com reciclagem de buffers do Tomcat.
   - Correlação interna por UUID (`req-<uuid>`) eliminando colisões de `id` entre clientes concorrentes.
   - Negociação limpa de `protocolVersion` (`2024-11-05`, `2025-*`, `2026-*`), fast-fail `< 5ms` para métodos desconhecidos (`-32601`) e interceptação de `roots/list` sem timeouts de 10s.
2. **Unificação de Tools e Serialização MCP ([CortexMcpTools.java](file:///mnt/g/Dev/Projetos/cortex/cortex-mcp/src/main/java/com/cortex/mcp/CortexMcpTools.java))**:
   - `ObjectMapper`, schemas JSON e handlers das 9 tools centralizados em uma única fonte da verdade compartilhada por Stdio ([CortexMcpServer.java](file:///mnt/g/Dev/Projetos/cortex/cortex-mcp/src/main/java/com/cortex/mcp/CortexMcpServer.java)) e HTTP/SSE ([McpConfig.java](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/config/McpConfig.java)).
   - `McpContentMixIn` elimina a duplicação de `"type":"text"` na serialização de `McpSchema.TextContent`.
3. **Sincronização Git Assíncrona em Virtual Threads ([ProjectRepository.java](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java))**:
   - `writePage` persiste o Markdown e atualiza o índice léxico BM25 e o grafo de conhecimento de forma síncrona (`< 500ms`), enfileirando o `syncToGit` de forma serializada por projeto em Virtual Threads do Java 25.

---

## 🔴 Achados de Severidade Alta (Bugs Latentes & Segurança)

### 1. Corrupção ou Exceção em `promoteRules` quando Regras Contêm `$` ou `\`
- **Local**: [`ProjectRepository.promoteRules` (linha 646)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L646)
- **Problema**:
  ```java
  existingContent = existingContent.replaceAll(
      "(?s)<!-- BEGIN CORTEX RULES -->.*?<!-- END CORTEX RULES -->\n?",
      rulesBlock.toString()
  );
  ```
  `String.replaceAll` interpreta `$` (referências a grupos de captura regex como `$1`, `${VAR}`) e `\` (caracteres de escape) no segundo parâmetro (`replacement`). Como regras do Cortex frequentemente contêm variáveis Maven/Bash (`${project.version}`, `$HOME`) ou caminhos Windows (`G:\Dev\Projetos`), chamar `promote_rules` em um `AGENTS.md` que já possua o bloco `<!-- BEGIN CORTEX RULES -->` lançará `IllegalArgumentException: Illegal group reference` ou removerá barras invertidas do texto promovido.
- **Correção**: Envolver o bloco substituto com `Matcher.quoteReplacement(rulesBlock.toString())`.

### 2. Risco de Path Traversal em `projectId`, `pageId` e `consumedRawIds`
- **Local**:
  - [`ProjectRepository.getProjectDir` (linha 217)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L217)
  - [`ProjectRepository.updatePageStatus` (linha 369)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L369)
  - [`ProjectRepository.writePage` (linha 446)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L446)
- **Problema**: `projectDir.resolve("raw").resolve(rawId + ".md")` e `Paths.get(..., "projects", projectId)` não validam se o identificador contém `../` ou separadores de diretório. Um argumento malformado em `consumed_raw_ids` (ex: `../pages/pagina-importante`) permitiria que `writePage` apagasse uma página consolidada em `pages/` em vez de uma nota em `raw/`.
- **Correção**: Validar identificadores (`projectId`, `rawId`, `supersedes`) contra `^[a-zA-Z0-9._-]+$` e verificar `resolvedPath.normalize().startsWith(expectedParent.normalize())`.

### 3. Risco de Bloqueio de Pipe e Falha de `git commit` Vazio em `runCommand` / `syncToGit`
- **Local**: [`ProjectRepository.syncToGit` e `runCommand` (linhas 937–983)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L937-L983)
- **Problema**:
  1. `runCommand` chama `p.waitFor(...)` **antes** de consumir `stdout`/`stderr`. Se um comando Git produzir mais saída que o buffer de pipe do SO (~64 KB), o subprocesso bloqueia esperando leitura do pipe até atingir o timeout de 15s.
  2. Se a árvore Git estiver limpa ("nothing to commit"), `git commit -m ...` retorna exit code `1`, lançando `IOException` antes de executar `git push`.
  3. Na inicialização do repositório (`linha 954`), `StrictHostKeyChecking=no` aceita chaves SSH trocadas sem validação; o padrão recomendado pelo OpenSSH moderno é `StrictHostKeyChecking=accept-new`.
- **Correção**: Configurar `pb.redirectErrorStream(true)`, ler o stream em paralelo (ou redirecionar para arquivo/memória antes de `waitFor`), e verificar se há mudanças (`git status --porcelain`) antes de invocar `git commit`.

### 4. Hardening do Container Docker (`Dockerfile` e `docker-compose.yml`)
- **Local**: [Dockerfile (linhas 1–14)](file:///mnt/g/Dev/Projetos/cortex/Dockerfile#L1-L14) e [docker-compose.yml (linhas 1–24)](file:///mnt/g/Dev/Projetos/cortex/docker-compose.yml#L1-L24)
- **Problema**:
  - O [Dockerfile](file:///mnt/g/Dev/Projetos/cortex/Dockerfile#L3) usa `eclipse-temurin:25-jdk` em vez de `eclipse-temurin:25-jre`, adicionando ~250 MB desnecessários à imagem de runtime (já que o JAR é pré-compilado).
  - O processo no container roda como `root` e sem `init: true` ou `HEALTHCHECK` configurado no Compose.
- **Correção**: Migrar a imagem base de runtime para `eclipse-temurin:25-jre`, adicionar `init: true` e `healthcheck` no `docker-compose.yml`.

---

## 🟠 Achados de Severidade Média (Concorrência, Consistência e Contratos)

### 5. Condição de Corrida e Duplicação de Chaves em `CortexConfigManager`
- **Local**: [CortexConfigManager.java (linhas 12–38)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/CortexConfigManager.java#L12-L38), [`ProjectRepository.bootstrap` (linhas 738–741)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L738-L741) e [`CommandController.java` (linhas 22, 80, 94)](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/CommandController.java#L22).
- **Problema**:
  - Embora `CortexConfigManager` seja um `@Service` Spring, ele é instanciado manualmente com `new CortexConfigManager()` em `ProjectRepository` e `CommandController`.
  - O ciclo `loadConfig()` → modificação → `saveConfig()` não é `synchronized` nem faz escrita atômica em arquivo temporário + `Files.move`.
  - Se um mesmo `projectId` (ex: `"cortex"`) for registrado via `bootstrap` com caminhos distintos ao longo do tempo (ex: `/mnt/g/Dev/Projetos/cortex` no host e `/projetos/cortex` no container), ambos permanecem como chaves no mapa `projects` (`rootPath -> projectId`), e `resolveTargetFileForProject` ([linha 677](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L677)) itera sobre um `HashMap` sem ordem garantida.
- **Correção**: Injetar `CortexConfigManager` via Spring (com fallback padrão em construtor sem argumentos para testes unitários), sincronizar `load/save` e remover entradas antigas do mesmo `projectId` ao atualizar seu `rootPath` canônico.

### 6. Formato Inconsistente de `log.md` e `index.md` entre `bootstrap()` e `writePage()`
- **Local**: [`ProjectRepository.bootstrap` (linhas 718–735)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L718-L735) vs. [`ProjectRepository.appendLog` / `updateIndex` (linhas 478–495)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L478-L495).
- **Problema**:
  - `bootstrap()` inicializa `log.md` com um cabeçalho de **tabela Markdown** (`| Data | Evento | ID |`), mas `appendLog()` grava blocos `## [timestamp] write | ...`, quebrando a formatação da tabela.
  - `bootstrap()` inicializa `index.md` com `_Nenhuma página consolidada ainda._`, e `updateIndex()` apenas faz `APPEND` de itens `- [id](pages/id.md)...` abaixo desse texto, deixando a mensagem `_Nenhuma página consolidada ainda._` permanentemente no arquivo mesmo após dezenas de páginas consolidadas.

### 7. Concorrência e Re-indexação Redundante no `ProjectWatcherService`
- **Local**: [`ProjectWatcherService.registerProject` (linhas 27–94 e 119–134)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectWatcherService.java#L27-L94)
- **Problema**:
  - `if (projectWatchers.containsKey(projectId)) return;` seguido de `projectWatchers.put(...)` não é atômico (`computeIfAbsent`), podendo iniciar duas threads virtuais e dois `WatchService` para o mesmo projeto em acesso concorrente inicial.
  - No `ENTRY_DELETE` ([linha 73](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectWatcherService.java#L73)), `filename.toString().replace(".md", "")` substitui todas as ocorrências de `".md"` no nome em vez de remover apenas o sufixo final.

### 8. Sincronização de Leitura/Escrita em `InMemoryLexicalIndex` e `KnowledgeGraph`
- **Local**: [InMemoryLexicalIndex.java (linhas 32–185)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/InMemoryLexicalIndex.java#L32-L185) e [KnowledgeGraph.java (linhas 26–142)](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/KnowledgeGraph.java#L26-L142).
- **Problema**:
  - `indexPage()`, `removePage()` e `clear()` são `synchronized`, mas `search()` (no índice) e `getBacklinks()` / `getOutlinks()` / `getTopReferenced()` (no grafo) **não** são sincronizados.
  - Como `indexPage(page)` chama `removePage(page.getId())` antes de reinserir os dados, uma thread executando `search()` simultaneamente pode observar o estado intermediário em que a página foi removida ou em que `pagesById` já contém a página mas `docTermFrequencies` ainda não foi preenchido.
  - Em `KnowledgeGraph.indexPage` ([linha 39](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/KnowledgeGraph.java#L39)), `outlinks.put(pageId, pageOutlinks)` armazena um `HashSet` comum (não thread-safe) que depois é lido sem lock em `getOutlinks` e `getTotalEdges`.
- **Correção**: Utilizar `ReentrantReadWriteLock` (ou `synchronized` nos métodos de leitura, dado que são operações em memória de microssegundos) e `Set.copyOf(pageOutlinks)`.

### 9. Divergência de Contratos na CLI (`cortex-cli`) vs MCP/REST
- **Local**: [PromoteCommand.java](file:///mnt/g/Dev/Projetos/cortex/cortex-cli/src/main/java/com/cortex/cli/PromoteCommand.java), [IngestCommand.java](file:///mnt/g/Dev/Projetos/cortex/cortex-cli/src/main/java/com/cortex/cli/IngestCommand.java), [ConsolidateCommand.java](file:///mnt/g/Dev/Projetos/cortex/cortex-cli/src/main/java/com/cortex/cli/ConsolidateCommand.java) e [BootstrapCommand.java](file:///mnt/g/Dev/Projetos/cortex/cortex-cli/src/main/java/com/cortex/cli/BootstrapCommand.java).
- **Problema**:
  - `BootstrapCommand` exige `--project` (`required = true`), enquanto MCP e REST inferem `project` automaticamente a partir de `--path` (`rootPath`).
  - `ConsolidateCommand` exibe apenas a contagem de arquivos brutos (`getPendingRawCount`), sem exibir o relatório clusterizado do `SmartSynthesizerService` (`repo.consolidateReport(projectId, topic)`).
  - `PromoteCommand` usa o nome `promote_rule` (singular) e `IngestCommand` usa `ingest`, sem aliases para `promote_rules` e `capture`.

---

## 🟡 Achados de Severidade Baixa (Boas Práticas Java 25, Spring Boot & Manutenibilidade)

| # | Arquivo / Símbolo | Observação | Recomendação |
|---|---|---|---|
| 10 | [`McpConfig.virtualThreadExecutor`](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/config/McpConfig.java#L72) e [`ProjectRepository.gitSyncExecutor`](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L53) | Ausência de lifecycle hook `@PreDestroy` para fechar os `ExecutorService` e o `McpSyncServer` no shutdown da aplicação. | Adicionar método `@PreDestroy void shutdown()` fechando `mcpServer` e os executores. |
| 11 | [`MarkdownParser.parseString`](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/MarkdownParser.java#L25-L38) | Usa `content.indexOf("---", startOfYaml + 3)` que pode casar com `---` no meio de um valor YAML. | Exigir delimitador de linha `^---\R` na abertura e `\R---(\R|$)` no fechamento do front-matter. |
| 12 | [`ProjectRepository.lint`](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/ProjectRepository.java#L570) vs [`KnowledgeGraph`](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/KnowledgeGraph.java#L15) | `lint()` usa regex `\[\[([a-zA-Z0-9_\-]+)\]\]` (sem espaços), enquanto `KnowledgeGraph` permite espaços (`\s`). | Unificar a constante `WIKILINK_PATTERN` e reutilizar o grafo na verificação de links quebrados. |
| 13 | Injeção de Dependências (`@Autowired`) | Uso de `@Autowired` em campos em [McpConfig.java](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/config/McpConfig.java#L64), [BootstrapCommandStrategy.java](file:///mnt/g/Dev/Projetos/cortex/cortex-api/src/main/java/com/cortex/api/strategy/BootstrapCommandStrategy.java#L10) e comandos de `cortex-cli`. | Padronizar injeção por construtor conforme a skill `java-springboot`. |
| 14 | Documentação Javadoc (`java-docs`) | Classes públicas como [MemoryPage.java](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/MemoryPage.java), [CortexStats.java](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/CortexStats.java) e [MarkdownParser.java](file:///mnt/g/Dev/Projetos/cortex/cortex-core/src/main/java/com/cortex/core/MarkdownParser.java) não possuem Javadoc de classe/método. | Adicionar Javadoc objetivo nas classes e métodos públicos do pacote `com.cortex.core`. |
