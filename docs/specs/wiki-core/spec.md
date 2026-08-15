# Wiki Core Specification

## Visão Geral
Esta capability define o modelo de dados, o motor de inteligência e a estrutura de armazenamento em disco do Cortex. O sistema armazena informações estritamente em arquivos Markdown simples com front-matter YAML e opera um índice lexical ponderado (BM25) e grafo de conhecimento em memória, sincronizados via Java NIO `WatchService` e Java 25 Virtual Threads.

---

## Estrutura de Diretórios
O Cortex opera num escopo por projeto, mapeado no arquivo central de configuração (`~/.cortex/config.yaml`). O conteúdo do projeto vive em `~/.cortex/projects/<project-id>/`.

```
~/.cortex/
  projects/
    <project-id>/
      schema.md          # Define convenções: tipos de página, front-matter, etc.
      index.md           # Catálogo/índice principal agrupado por tópico.
      log.md             # Log cronológico append-only das capturas.
      raw/               # Observações brutas geradas por sessão/evento (imutáveis).
      pages/             # Páginas curadas pelo processo de consolidação.
      rules/             # Regras duráveis (candidatas à promoção).
  config.yaml            # Mapeamento do caminho real do projeto para <project-id>.
```

---

## Motor de Inteligência em Memória

### 1. In-Memory Lexical Index (BM25)
- **Fórmula de Ranking**: BM25 clássico ($k_1 = 1.2, b = 0.75$) com cálculo de IDF:
  $$\text{IDF}(q) = \ln\left(1 + \frac{N - n(q) + 0.5}{n(q) + 0.5}\right)$$
- **Tokenização Inteligente**: Suporta decomposição em CamelCase (ex: `DeserializationFeature` $\to$ `deserialization`, `feature`), divisão por hífens e underscores, e remoção de stopwords em PT/EN.
- **Filtros Facetados**: Suporta sintaxe combinada na query:
  - `type:<fact|decision|rule|gotcha|note>`
  - `tags:<tag_name>`
  - `status:<active|superseded>`
- **Ponderação de Relevância**:
  - Boost de $3\times$ para termos no ID e $2\times$ para tags.
  - Multiplicador de cobertura de múltiplos termos ($1.5\times$ para match de 100% dos termos da query).
  - Boost por tipo ($1.3\times$ para `rule` e `gotcha`).
  - Bônus por recência (+0.5 para memórias criadas em menos de 2 dias).
- **Extração de Snippets**: Geração de trechos contextuais destacando as frases onde os termos da busca mais ocorrem.

### 2. Knowledge Graph & Wikilinks Bidirecionais
- **Sintaxe Wikilink**: Suporte a referências do tipo `[[target-id]]` ou `[[topico]]`.
- **Rastreamento Bidirecional**: O `KnowledgeGraph` indexa arestas direcionadas (`outlinks`) e referências reversas (`backlinks`).
- **Análise Preditiva de Impacto**: Ao criar uma nova versão de uma página que substitui (`supersedes`) um ID anterior, o Cortex identifica quais outras páginas curadas dependiam da antiga e emite avisos de impacto.

### 3. Cache Reativo via NIO & Java 25 Virtual Threads
- O `ProjectWatcherService` registra listeners de `java.nio.file.WatchService` nos subdiretórios `pages/`, `raw/` e `rules/`.
- Cada projeto monitorado executa seu loop de eventos em uma **Virtual Thread** (`Thread.ofVirtual()`), atualizando o `InMemoryLexicalIndex` e o `KnowledgeGraph` com latência zero quando arquivos são editados externamente.

### 4. Smart Synthesizer (Topic Clustering)
- O `SmartSynthesizerService` agrupa notas brutas em `raw/` por co-ocorrência de tags e vocabulário semântico.
- Permite consolidação incremental tópico por tópico através do comando `consolidate(project, topic="...")`.

---

## Modelo de Dados (Front-matter YAML)
Toda página em `raw/` e `pages/` contém o seguinte schema no bloco YAML inicial:

```yaml
---
id: "20260815-145536-java25-6e29"
type: fact | decision | rule | gotcha | note
project: <project-id>
tags: [tag1, tag2]
status: active | superseded
supersedes: null          # ID do registro anterior substituído
source: session:abc123 | manual | ingest:<url>
created_at: YYYY-MM-DDTHH:MM:SSZ
updated_at: YYYY-MM-DDTHH:MM:SSZ
---
```

### Tipos de Memória (`type`)
- `fact`: Fatos verificáveis e imutáveis sobre o negócio ou sistema.
- `decision`: Escolhas de arquitetura, ferramentas ou design, documentando o "porquê".
- `rule`: Normas imperativas a serem seguidas pelo agente em interações futuras.
- `gotcha`: Dificuldades encontradas, bugs silenciosos ou pegadinhas de framework/domínio.
- `note`: Conhecimento genérico ou anotação sem enquadramento estrito.

---

## Cadeia de Supersessão
Nenhum arquivo curado é deletado destrutivamente. Quando um contexto evolui, um novo arquivo substitui o anterior, referenciando o ID antigo no campo `supersedes` e alterando o `status` do antigo para `superseded` com score reduzido na busca.
