---
spec: 006
titulo: Divergência de contratos na CLI, imagem Docker com JDK completo e ausência de padronização de injeção por construtor e Javadoc
status: implementada
atualizado: 2026-10-18
---
# 006 — Divergência de contratos na CLI, imagem Docker com JDK completo e ausência de padronização de injeção por construtor e Javadoc

## Objetivo
Alinhar os contratos e comandos do módulo `cortex-cli` com as ferramentas MCP/REST, otimizar e endurecer a execução do container Docker (`eclipse-temurin:25-jre`, `init: true` e `healthcheck`), e padronizar injeção por construtor e documentação Javadoc em todos os módulos.

## Contexto
Originada do Code Review completo registrado em [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md) em `2026-10-17` (Achados **#4, #9, #13 e #14**):

1. **Achado #4 — Imagem Docker com JDK completo e ausência de `init`/`healthcheck`** (`Dockerfile:3`, `docker-compose.yml:1-24`):
   - O `Dockerfile` utiliza `FROM eclipse-temurin:25-jdk` apesar de copiar o fat JAR já compilado (`cortex-api-1.0-SNAPSHOT-exec.jar`), carregando ~250 MB extras do compilador e ferramentas de desenvolvimento para o container de runtime.
   - O `docker-compose.yml` não declara `init: true` (importante para coleta de subprocessos `git`/`ssh` órfãos quando o PID 1 é `sh`/`java`) nem `healthcheck` para monitorar a prontidão do serviço na porta `8080`.
2. **Achado #9 — Divergência de contratos no módulo `cortex-cli` vs MCP/REST**:
   - `BootstrapCommand.java:18` declara `@Option(names = {"--project"}, required = true)`, impedindo que o usuário rode `cortex bootstrap --path /caminho/do/repo` sem `--project` (diferente do MCP e da API REST após a Spec 002).
   - `ConsolidateCommand.java:23-38` apenas exibe `repo.getPendingRawCount(projectId)` com uma mensagem estática, ignorando o `repo.consolidateReport(projectId, topic)` e o agrupamento inteligente do `SmartSynthesizerService`.
   - `PromoteCommand.java:12` registra apenas o nome `promote_rule` (singular) e `IngestCommand.java:14` registra apenas `ingest`, sem aceitar os aliases `promote_rules` e `capture` usados no MCP e na API REST.
3. **Achado #13 — Uso de `@Autowired` em campos em vez de injeção por construtor** (`McpConfig.java:64`, `BootstrapCommandStrategy.java:10`, `CaptureCommandStrategy.java`, `ProjectRepository.java:36-40` e classes de `cortex-cli`):
   - A skill `java-springboot` estabelece injeção por construtor (sem `@Autowired` em campos) para facilitar testabilidade fora do container Spring e garantir dependências explícitas.
4. **Achado #14 — Ausência de Javadoc em classes públicas** (`MemoryPage.java`, `CortexStats.java`, `MarkdownParser.java`, `CortexConfig.java`, `CortexConfigManager.java`, `CommandController.java`):
   - A skill `java-docs` exige que os tipos públicos do projeto possuam comentários Javadoc claros descrevendo sua responsabilidade e contratos principais.

## Comportamento esperado
1. **Hardening e otimização Docker**:
   - O `Dockerfile` deve usar `eclipse-temurin:25-jre` como imagem base de runtime, mantendo `git`, `openssh-client` e `ca-certificates` instalados e `-XX:+UseCompactObjectHeaders` no entrypoint.
   - O `docker-compose.yml` deve configurar `init: true` e um bloco `healthcheck` verificando a disponibilidade HTTP da API na porta `8080`.
2. **Paridade de contratos na CLI (`cortex-cli`)**:
   - `BootstrapCommand` deve tornar `--project` opcional (`required = false`), delegando a inferência do `projectId` a partir de `--path` (ou `user.dir`) para `repo.bootstrap(projectId, rootPath)`.
   - `ConsolidateCommand` deve aceitar a opção opcional `--topic` e imprimir o relatório completo retornado por `repo.consolidateReport(projectId, topic)`.
   - `PromoteCommand` deve aceitar tanto `promote_rule` quanto `promote_rules` (`aliases = {"promote_rules"}`), e `IngestCommand` deve aceitar tanto `ingest` quanto `capture` (`aliases = {"capture"}`).
3. **Injeção por construtor e Javadoc**:
   - Todas as classes gerenciadas pelo Spring em `cortex-core`, `cortex-api` e `cortex-cli` devem usar injeção por construtor em vez de `@Autowired` em campos (preservando construtores padrão onde necessário para testes unitários diretos).
   - Todas as classes públicas do projeto devem possuir Javadoc de classe válido.

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** O arquivo `Dockerfile` utiliza `FROM eclipse-temurin:25-jre` e não contém referência a `:25-jdk`.
- **CA-2:** O arquivo `docker-compose.yml` declara `init: true` e um bloco `healthcheck` funcional para o serviço `cortex-api`.
- **CA-3:** Executar `BootstrapCommand` via Picocli passando apenas `--path <diretorio>` (sem `--project`) conclui com exit code `0` e inicializa o projeto inferindo o `projectId` pelo nome do diretório.
- **CA-4:** Executar `ConsolidateCommand` via Picocli para um projeto com notas em `raw/` retorna exit code `0` e imprime na saída padrão o relatório de clusters gerado por `ProjectRepository.consolidateReport` (respeitando o filtro `--topic` quando informado).
- **CA-5:** O `CommandLine` principal (`CortexCommand`) resolve e executa com exit code `0` os subcomandos chamados tanto pelos nomes legados (`ingest`, `promote_rule`) quanto pelos nomes unificados do MCP/REST (`capture`, `promote_rules`).
- **CA-6:** Nenhuma classe em `src/main/java` dos módulos `cortex-core`, `cortex-mcp`, `cortex-api` e `cortex-cli` possui anotação `@Autowired` aplicada diretamente sobre atributos (`Field`), utilizando exclusivamente injeção por construtor.
- **CA-7:** Todas as classes públicas em `src/main/java` possuem bloco Javadoc de cabeçalho (`/** ... */`) antes da declaração do tipo.

## Fora de escopo
- Correções de Path Traversal, regex em `promoteRules` e subprocesso Git — tratadas na [Spec 004](004-seguranca-integridade-e-git-core.md).
- Sincronização de concorrência em índices, watcher e `CortexConfigManager` — tratada na [Spec 005](005-concorrencia-indices-watcher-e-ciclo-de-vida.md).

## Invariantes tocadas
- **Princípios de Memória LLM Wiki #4 (Sem Infraestrutura Desnecessária / KISS)**: reduz o tamanho da imagem do container usando JRE em vez de JDK e mantém paridade simples entre CLI, REST e MCP sem novas dependências.

## Testes que cobrem esta spec
- `cortex-cli/src/test/java/contract/Spec006AlinhamentoCliDockerPadroesTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6, CA-7 (a ser criado na implementação).

## Notas
- Spec criada em estado `rascunho` a partir dos achados #4, #9, #13 e #14 do [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md).
