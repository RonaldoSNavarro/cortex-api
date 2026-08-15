# CLI Java Specification

## Visão Geral
A CLI do Cortex permite ao desenvolvedor interagir diretamente com o sistema de memória, acionar ingestão manual de conhecimento, inspecionar métricas do cérebro e efetuar buscas pontuais pelo terminal.

---

## Requisitos Técnicos
- Baseado em **Java 25** e **Spring Boot 4.1.0**.
- Utiliza **Picocli 4.7.x** para definição de comandos, suporte a autocompletion e formatação rica de terminal.
- Reutiliza a camada de Domínio unificada do `cortex-core`.

---

## Comandos Disponíveis

### `cortex query`
- **Descrição:** Realiza busca lexical ponderada (BM25) com pontuação de relevância, snippets e tags.
- **Uso:** `cortex query --project <id> "<termos ou filtros>"`
- **Exemplo:** `cortex query --project consorcio "type:rule lance embutido"`

### `cortex stats`
- **Descrição:** Exibe o dashboard de métricas de saúde, taxa de consolidação, distribuição de tipos, top tags e conexões do grafo de conhecimento.
- **Uso:** `cortex stats --project <id>`

### `cortex init`
- **Descrição:** Inicializa a estrutura do projeto no disco (`~/.cortex/projects/<id>`) e gera o `schema.md`.
- **Uso:** `cortex init --project <id>`

### `cortex ingest`
- **Descrição:** Captura informações brutas a partir de arquivos externos ou URLs para a pasta `raw/`.
- **Uso:** `cortex ingest --project <id> --type <note|decision|...> <fonte>`

### `cortex consolidate`
- **Descrição:** Monitora e sugere a consolidação das informações brutas em clusters temáticos.
- **Uso:** `cortex consolidate --project <id>`

### `cortex lint`
- **Descrição:** Executa a verificação semântica de sanidade da base, reportando *dangling wikilinks* e débitos de consolidação.
- **Uso:** `cortex lint --project <id>`

### `cortex handoff`
- **Descrição:** Gera o relatório condensado de transferência de contexto para outro agente.
- **Uso:** `cortex handoff --project <id> --target <agente>`

### `cortex promote`
- **Descrição:** Injeta as regras ativas nos arquivos de contexto da IDE (`AGENTS.md` ou `CLAUDE.md`).
- **Uso:** `cortex promote --project <id> --file <caminho>`
