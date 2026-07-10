# Wiki Core Specification

## Visão Geral
Esta capability define o modelo de dados e a estrutura de armazenamento em disco, que atua como o backend primário de persistência do Cortex MVP. O sistema armazena informações estritamente em arquivos Markdown.

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

## Arquivo de Configuração (`config.yaml`)
Localizado na raiz (`~/.cortex/config.yaml`), ele mantém um mapa de caminhos locais no sistema de arquivos para o ID do projeto no Cortex. Quando a CLI ou o Servidor MCP são invocados a partir do diretório raiz de um projeto, eles consultam este mapa.

**Schema esperado:**
```yaml
projects:
  "f:/Dev/Projetos/cortex": "cortex-self"
  "C:/Workspace/consorcio-api": "consorcio-brasil"
```

## Skill de Convenções da Wiki (`SKILL.md`)
Os agentes de IA precisam entender as regras de como operar a Wiki (quando usar cada tipo de memória, como consolidar, como ler). 
- O arquivo `schema.md` (ou `SKILL.md` dentro de um diretório `.agents/skills/cortex-conventions/`) conterá as regras descritas no item 14.1 dos requisitos.
- A ferramenta `cortex init` será responsável por ejetar/criar esse arquivo de Skill base durante a inicialização do projeto, para que o agente já passe a carregá-lo nativamente.

## Modelo de Dados (Front-matter YAML)
Toda página em `raw/` e `pages/` **deve** conter o seguinte schema no bloco YAML inicial:

```yaml
---
id: "01J..."              # Identificador único (ex: ULID ou UUID)
type: fact | decision | rule | gotcha | note
project: <project-id>
tags: [tag1, tag2]
status: active | superseded
supersedes: null          # id do registro anterior (para manter histórico de modificações)
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

## Cadeia de Supersessão
Nenhum arquivo curado é deletado para edição destrutiva de histórico. Quando um contexto evolui, um novo arquivo substitui o velho, referenciando o ID antigo no campo `supersedes`, e alterando o `status` do antigo para `superseded`.
