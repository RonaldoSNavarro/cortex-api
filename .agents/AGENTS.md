# Project Rules: Model Context Protocol (MCP) Java Integration

When building or modifying Model Context Protocol (MCP) server endpoints in this Java project, always adhere to the following implementation constraints:

## 1. Jackson Deserialization (FAIL_ON_UNKNOWN_PROPERTIES)
- **Constraint**: Always configure Jackson `ObjectMapper` instances used for MCP message parsing with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` set to `false`.
- **Rationale**: MCP clients (such as the Go SDK) often send client-specific capabilities (e.g., `"elicitation":{"form":{},"url":{}}`) in their `initialize` request. The Java MCP SDK's static class definitions (like `ClientCapabilities`) only model standard fields (`experimental`, `roots`, `sampling`). If `FAIL_ON_UNKNOWN_PROPERTIES` is enabled (the default), Jackson will throw an exception and fail the handshake.

## 2. SSE POST Response Bridging
- **Constraint**: For SSE transports, intercept client-to-server POST requests and return the actual JSON-RPC response in the HTTP POST body.
- **Rationale**: While the official MCP SSE specification states that POST responses can have an empty body (status 202), strict client libraries (such as Go's `mcp-go`) expect the matching JSON-RPC response directly in the HTTP POST response body and fail to decode empty or request-echoed bodies. Ensure you intercept outgoing messages (using `sendMessage`), register futures per-request ID, block the POST request handler until the response is captured, and write the response directly to the POST response body.

# Princípios de Memória (AI Memory / LLM Wiki)

Sempre que projetar, codificar ou sugerir arquiteturas para sistemas de memória de IA, agentes ou recuperação de contexto (como o projeto Cortex), é ESTRITAMENTE PROIBIDO sugerir ou implementar "RAG tradicional" (Retrieval-Augmented Generation) baseado em fatiamento de texto (Chunking) e bancos vetoriais opacos.

Siga obrigatoriamente as premissas de Karpathy (LLM Wiki) e Akita (ai-memory):
1. **Unidade Atômica**: O arquivo Markdown (.md) é a unidade fundamental. O conhecimento deve ser acumulado, consolidado e mantido em arquivos legíveis por humanos e versionados via Git.
2. **Consolidação em vez de Recuperação de Fragmentos**: A IA não deve recuperar fragmentos isolados de logs brutos. A IA deve ler logs brutos, processá-los e reescrever páginas de wiki coerentes.
3. **Índices Simples**: Para buscas, prefira abordagens simples e diretas como arquivos `index.md`, `log.md`, ou buscas textuais (FTS5). Se um banco vetorial for absolutamente necessário, ele deve agir APENAS como um índice que aponta para o ID do arquivo (1 vetor = 1 arquivo inteiro), preservando a leitura do front-matter e do documento completo pelo LLM.
4. **Sem Infraestrutura Desnecessária**: Evite sobrecarregar o usuário com dependências (bancos de dados vetoriais, containers extras) a menos que a escala exija. Mantenha o sistema "KISS".

## Fluxo de desenvolvimento — SDD

Este projeto segue **Spec Driven Development**: toda mudança de comportamento nasce de uma spec
em `specs/NNN-nome.md`, **antes** do código. As regras, a convenção de nomes e o **índice de
specs** (que é o changelog do projeto) estão em `specs/README.md` — leia-o antes de implementar.

- **Sem spec, sem código.** Se o pedido muda o que o sistema faz, o primeiro entregável é a spec.
- Critérios de aceite (`CA-n`) são afirmações **verificáveis**; testes citam o número no nome.
- Spec e implementação são revisadas no **mesmo PR**.

<!-- BEGIN CORTEX RULES -->
# Regras Promovidas do Projeto Cortex

## Rule [20261018-160513-sdd-2181]
# Padrão Spec Driven Development (SDD) e Regime TDD Estrito

O repositório Cortex adota formalmente o padrão **Spec Driven Development (SDD)** definido em `.agents/skills/skill-sdd` e documentado no índice de especificações `specs/README.md`.

## Regras Obrigatórias:
1. **Sem Spec, Sem Código**: Toda mudança de comportamento deve obrigatoriamente nascer de uma spec em `specs/NNN-nome.md` antes de qualquer linha de código de produção ser escrita.
2. **Critérios de Aceite Verificáveis (CA-n)**: Toda spec define critérios de aceite atômicos e auditáveis (`CA-1`, `CA-2`, etc.).
3. **Regime TDD Estrito**: Cada critério `CA-n` deve possuir testes de contrato correspondentes em `src/test/java/contract/SpecNNNTituloTest.java` seguindo o ciclo Red-Green-Refactor.
4. **Unidade de Revisão**: A especificação, os testes de contrato e o código de produção são revisados e integrados no mesmo pull request / commit.

## Rule [7b949291-b728-4292-b2bf-419d4e7d8e24]
O Cortex foi atualizado para rejeitar ativamente padrões de RAG tradicional (Retrieval-Augmented Generation) que utilizam particionamento de texto (chunking).

Ficou estabelecido, inspirado pelas implementações de Andrej Karpathy (LLM Wiki) e Fábio Akita (ai-memory), que a unidade atômica de memória é o arquivo Markdown legível. A inteligência do sistema reside em **consolidar as informações através de leitura e re-escrita ativas** (mantendo arquivos coerentes), e não em retalhar arquivos e buscar vetores de fatias de texto opacas.

Qualquer banco vetorial adicionado futuramente deverá atuar puramente como um índice de página cheia (1 vetor de documento completo aponta para 1 ID de arquivo), preservando a leitura posterior por LLMs do front-matter original e do arquivo completo no disco.

<!-- END CORTEX RULES -->
