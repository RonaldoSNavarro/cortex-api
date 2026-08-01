# CLAUDE.md - Cortex Memory & Wiki System

O **Cortex** é um sistema de memória ativa e wiki pessoal em formato Markdown, projetado para servir como o cérebro persistente de longo prazo para agentes de IA (**Claude Code**, **Google Antigravity**, **OpenAI Codex**).

---

## 🛠️ Comandos de Desenvolvimento

### Build e Compilação (Maven / Java 21)
```bash
# Compilar e gerar JARs de todos os módulos
mvn clean package -DskipTests

# Executar suíte de testes unitários e de integração
mvn test
```

### Execução da Aplicação (Spring Boot + MCP SSE Server)
```bash
# Subir via Docker Compose (Porta 8080)
docker-compose up -d --build cortex-api

# Ou rodar o Spring Boot localmente via Maven
mvn spring-boot:run -pl cortex-api
```

### CLI do Cortex (`cortex-cli`)
```bash
# Consultar memória do projeto
java -jar cortex-cli/target/cortex-cli-1.0-SNAPSHOT.jar query --project=cortex "sua busca"

# Promover regras consolidadas para a IDE/Agentes
java -jar cortex-cli/target/cortex-cli-1.0-SNAPSHOT.jar promote --project=cortex --file=.agents/AGENTS.md
```

---

## 🏗️ Arquitetura do Repositório

- **`cortex-core`**: Domínio central, manipuladores de arquivos Markdown, parsing de YAML frontmatter, indexação de memória e motor de busca.
- **`cortex-api`**: Aplicação Spring Boot que expõe a API REST e o servidor **Model Context Protocol (MCP)** via SSE (`http://localhost:8080/mcp/sse`).
- **`cortex-cli`**: Interface de linha de comando (PicoCLI) para operações diretas de memória e promoção de regras.
- **`cortex-mcp`**: Servidor MCP independente empacotado para execução em transporte stdio.

---

## 🔌 Ferramentas MCP Disponíveis (`cortex`)

Quando o agente está conectado ao Cortex MCP Server (via SSE ou stdio), ele deve utilizar as seguintes ferramentas:

1. `query`: Pesquisa na base de memória do projeto por termos, tags ou tipos (`rule`, `decision`, `gotcha`, `fact`).
2. `capture`: Registra uma nova descoberta ou dado bruto na memória.
3. `write_page`: Escreve/atualiza uma página curada na wiki do projeto.
4. `promote_rules`: Injeta as regras ativas consolidadas da memória no arquivo de contexto do projeto (`.agents/AGENTS.md` ou `AGENTS.md`).
5. `lint`: Executa diagnósticos de integridade na memória (links quebrados, frontmatter ausente).
6. `bootstrap`: Inicializa a estrutura da base de memória em um novo projeto.
7. `handoff`: Gera relatórios sintéticos de transferência de contexto para a próxima sessão de trabalho.

---

## 📜 Princípios de Memória (AI Memory / LLM Wiki)

Siga obrigatoriamente as diretrizes da **LLM Wiki** (Karpathy) e **ai-memory** (Akita):
1. **Unidade Atômica**: O arquivo Markdown (`.md`) com frontmatter YAML é a unidade fundamental de conhecimento.
2. **Consolidação em vez de RAG Vetorial Opaco**: Não dependa de chunking de texto ou vetores opacos. Processe logs/rascunhos e reescreva páginas de wiki claras e legíveis por humanos.
3. **Persistência em Git**: Toda a memória do Cortex reside em `.agents/` no projeto do usuário, legível e versionada no repositório.
