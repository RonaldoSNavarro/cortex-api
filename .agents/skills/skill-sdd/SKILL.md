---
name: spec-driven-development
description: Conduz o desenvolvimento por Spec Driven Development (SDD) — toda mudança de comportamento nasce de uma spec numerada em `specs/NNN-nome.md`, com critérios de aceite verificáveis (CA-n), invariantes declaradas, fora de escopo explícito e rastreabilidade CA→teste. Use quando o usuário pedir para criar/escrever/atualizar uma spec, iniciar uma feature nova, planejar uma mudança de comportamento, registrar dívida técnica ou achados de code review, adotar/instalar o padrão SDD num projeto, ou quando for implementar algo num repositório que já tenha o diretório `specs/`. Acione também quando o pedido for "vamos implementar X" num projeto que segue SDD — a spec vem antes do código.
---

# Spec Driven Development (SDD)

Padrão de desenvolvimento em que **nenhuma mudança de comportamento começa pelo código**. Começa
por um documento curto, numerado e versionado junto do código, que declara o que o sistema passará
a fazer e — principalmente — **como saber que ele faz**.

Extraído de um projeto real com 112 specs em produção. Funciona com ou sem TDD estrito, em
qualquer linguagem.

## Por que isto existe (leia antes de aplicar mecanicamente)

Três problemas concretos que a spec resolve, e que nenhum commit resolve sozinho:

1. **"Por que isto é assim?" seis meses depois.** O código diz *o quê*. O `git log` diz *quando*.
   A spec é o único lugar que guarda o *porquê* — a evidência, a alternativa descartada, o custo
   que se aceitou pagar. Sem ela, a próxima pessoa (ou o próximo agente) "conserta" a decisão.
2. **Escopo que escorre.** Uma seção obrigatória de *Fora de escopo* transforma "isso a gente vê
   depois" numa afirmação escrita, com motivo. O que não está escrito volta em toda revisão.
3. **"Está pronto" sem régua.** Critério de aceite numerado é uma afirmação que alguém pode
   verificar e reprovar. "Melhorar a performance da busca" não é; "a busca de 2+ palavras devolve
   resultado quando o AND estrito devolveria zero" é.

O ganho colateral, que na prática vira o maior: **o índice de specs é o changelog do projeto** —
o único documento que conta a história do sistema em ordem, com status e ponteiro para os testes.

## Quando acionar

- O usuário pede uma feature, correção de comportamento, mudança de contrato/rota/flag/tela.
- O usuário pede explicitamente uma spec, ou pede para adotar SDD num projeto.
- Um code review produziu achados que não cabem no PR atual — viram spec, não TODO solto.
- Existe `specs/` no repositório: **antes de editar código, verifique se há spec para o que foi
  pedido**. Se não há, a spec é o primeiro entregável.

**Quando NÃO acionar:** correção de typo, ajuste de formatação, atualização de dependência sem
mudança de comportamento, e qualquer coisa que não altere o que o sistema faz do ponto de vista de
quem o usa. Spec para não-mudança é burocracia, e burocracia mata o padrão pelo tédio.

## Instalar o padrão num projeto

Rode o instalador desta skill a partir da raiz do projeto alvo:

```bash
bash ~/.claude/skills/spec-driven-development/install.sh --dry-run   # mostra o que faria
bash ~/.claude/skills/spec-driven-development/install.sh             # instala
```

Ele cria `specs/README.md` (regras + índice), `specs/_template.md`, e **acrescenta ao
`CLAUDE.md`/`AGENTS.md` do projeto a seção que aponta para `specs/README.md`**. Esse último passo
não é decoração: sem ele o agente não abre `specs/` por conta própria, e o padrão não sobrevive à
primeira sessão nova.

É **idempotente** — nunca sobrescreve arquivo existente, e não acrescenta a seção duas vezes.

| Opção | Efeito |
|---|---|
| `--target DIR` | Projeto alvo (default: diretório atual). |
| `--lang L` | `python`, `go`, `node`, `ruby`, `java`, `csharp`, `generic`. Ajusta a **convenção de nomes de teste** no README. Detectado sozinho por `pyproject.toml`/`go.mod`/`package.json`/`Gemfile`/`pom.xml`/`*.csproj`. |
| `--tdd strict\|relaxed` | Regime de teste registrado no README (default `relaxed` — spec obrigatória, red→green não). |
| `--version-bump` | Inclui a regra de versão `0.<NNN>.<correção>` no fluxo. Só faça isso se o projeto publica um artefato versionado. |
| `--first-spec "Título"` | Já cria `specs/001-<slug>.md` preenchida no frontmatter e registrada no índice. Recusa (e diz o próximo número livre) se o projeto já tiver specs — numeração nunca é reaproveitada. |
| `--agent-file CLAUDE.md\|AGENTS.md\|none` | Onde fisgar o padrão. Default: o que já existir; se nenhum existir, cria `CLAUDE.md`. |
| `--dry-run` | Mostra o plano sem escrever nada. |

**Antes de rodar, decida duas coisas com o usuário** (as duas mudam o `specs/README.md` que fica
versionado no repo, e mudá-las depois exige reescrever o arquivo):

1. **Regime de teste** — TDD estrito por CA, ou testes obrigatórios só nos CAs de invariante?
2. **Versão amarrada à spec** — o projeto publica artefato versionado onde isso se paga?

Instalação manual, se preferir: copie `templates/` para `specs/` e substitua os tokens
`{{TDD_BLOCK}}`, `{{BUMP_BLOCK}}`, `{{CONV_BLOCK}}` e `{{INDEX_ROWS}}` no README.

Se o projeto já tem histórico, **não** escreva specs retroativas para tudo. Comece a numerar do
001 na primeira feature nova. Spec retroativa só se paga para a decisão que ainda gera dúvida.

## O ciclo de uma feature

1. **Spec.** `specs/NNN-slug.md` a partir do template. Numeração sequencial, **nunca reutilizada**
   (nem quando a spec é descartada — o número morre com ela).
2. **Registro no índice.** Linha em `specs/README.md`. Uma spec fora do índice é uma spec que
   ninguém encontra.
3. **Código.** Implemente o que a spec descreve — e só isso. Se durante a implementação aparecer
   comportamento não previsto, **volte e edite a spec** (ela ainda não foi mergeada; é barato).
4. **Verificação.** Cada `CA-n` é verificado antes do merge: por teste automatizado quando o
   critério toca invariante ou contrato público; por verificação manual **documentada no PR**
   quando o teste não paga o custo.
5. **Fechamento.** Status → `implementada`, seção *Testes que cobrem esta spec* preenchida,
   índice atualizado, versão bumpada (se o projeto usar a convenção abaixo).

Spec e implementação são revisadas **no mesmo PR**. Spec mergeada antes do código vira ficção;
código mergeado antes da spec derrota o padrão inteiro.

## Anatomia da spec

Use `templates/_template.md`. O que cada seção precisa entregar — e como ela falha:

| Seção | Entrega | Como falha na prática |
|---|---|---|
| **Objetivo** | Uma frase: que problema, para quem. | Vira resumo da implementação ("adicionar campo X"). O objetivo é o problema, não a solução. |
| **Contexto** | O *porquê agora*, com **evidência**: reprodução, medição, linha de código, relato de campo. | Vira opinião ("acho que ficaria melhor"). Contexto sem evidência não sustenta a decisão depois. |
| **Comportamento esperado** | Prosa do que o sistema passa a fazer: entradas, saídas, efeitos. | Vira lista de tarefas de implementação. Descreva o comportamento observável, não os arquivos que você vai tocar. |
| **Critérios de aceite** | `CA-n` verificáveis. Ver a régua abaixo. | Viram desejos. É o defeito mais comum e o mais caro. |
| **Fora de escopo** | O que a spec deliberadamente não cobre, **com motivo**. | Fica vazia. Seção vazia aqui é sinal de que o escopo não foi pensado. |
| **Invariantes tocadas** | Quais regras inegociáveis do projeto a feature encosta, e como as preserva. | É pulada em feature "pequena" — justamente onde a invariante quebra sem ninguém ver. |
| **Testes que cobrem esta spec** | Mapa `arquivo → CA-n`. Preenchido no fechamento. | Fica genérica ("tem testes"). Sem o mapa, não há rastreabilidade. |

### Contexto: traga a evidência, não a impressão

O que faz uma spec valer daqui a um ano é o contexto ter **prova**. Formas que funcionam:

- **Reprodução literal.** A entrada e a saída erradas, coladas:
  ```
  'Sintoma-chave=Aplicacao ja esta ativa'  ->  'Sintoma-chave=[REDACTED] ja esta ativa'
  ```
- **Medição.** "57.874 chars 'dentro' do orçamento de 60.000 viravam 75.367 no cliente."
- **Ponteiro de código.** "`config.py:184` — o default é `False`."
- **Tabela do que já existe** vs. **o que falta**, para não refazer o que está pronto.

Registre também **o que a implementação inicial errou e foi corrigido no review** — isso impede que
a próxima pessoa reintroduza o erro por parecer mais simples.

## A régua dos critérios de aceite

Um `CA-n` é uma **afirmação verificável no sistema pronto** — algo que se pode reprovar. Régua:
*consigo escrever o teste (ou o passo de verificação manual) lendo só esta linha?*

| ❌ Desejo | ✅ Critério |
|---|---|
| A busca fica mais relevante. | Query de 2+ palavras devolve resultado quando o AND estrito devolveria zero, e o estágio usado volta no campo `matched`. |
| Cookies mais seguros em produção. | URL pública em `https://` faz `cookie_secure` valer `True` sem configuração adicional; um `False` explícito continua respeitado. |
| Tratar erro de configuração. | URL malformada (sem esquema, com caminho, com espaço) é recusada no boot com mensagem explícita — não silenciosamente ignorada. |
| Não quebrar quem já usa. | Sem a variável definida, tudo se comporta exatamente como hoje — nenhuma regressão para quem roda local. |

Regras práticas:

- **Um comportamento por CA.** CA com "e" no meio quase sempre são dois.
- **Inclua o CA de não-regressão.** "Com a flag ausente, nada muda" é o critério que mais salva.
- **Inclua o caso de erro.** Entrada malformada, dependência fora do ar, permissão ausente.
- **Nomeie o observável.** O nome do campo, o código HTTP, a mensagem, o arquivo escrito — o CA
  precisa apontar para algo que existe fora da cabeça de quem escreveu.
- **Numeração estável.** `CA-7` continua sendo `CA-7` para sempre. CA acrescentado depois entra
  como `CA-9b` ou no fim da lista; CA removido é marcado como revertido, não renumerado — testes e
  PRs já citam o número.
- 5 a 15 CAs é a faixa saudável. Menos que 3, a spec provavelmente não precisava existir; mais de
  20, são duas specs.

## Rastreabilidade CA → teste

A convenção que faz a rastreabilidade funcionar sem ferramenta:

- Arquivo de teste espelha o número da spec: `tests/contract/test_NNN_<slug>.py`,
  `test/contract/NNN_<slug>_test.go`, `spec/contract/NNN_<slug>_spec.rb` — o que a linguagem usar.
- Cada caso cita o critério no **nome**: `test_ca4_https_liga_cookie_secure`.
- **Docstring/comentário do arquivo lista todos os CAs cobertos**, em uma linha cada. É o resumo
  que a próxima pessoa lê antes de mexer.
- A spec, no fechamento, lista o caminho inverso: `arquivo → CA-1 … CA-9`.

Três tipos de teste, se o projeto os separar por marcador: **unit** (lógica pura), **integration**
(sobe o sistema), **contract** (verifica os CA-n de uma spec). O teste de contrato é o que morre
junto com a spec — os outros sobrevivem à refatoração.

## Numeração, status e versão

**Status:** `rascunho` → `aprovada` → `implementada`. Use também estados honestos quando for o
caso: `implementada (CA-N revertida)`, `descartada`. O estado que mente é pior que o estado feio.

**Numeração:** sequencial, monotônica, nunca reutilizada. Ordem de *criação*, não de implementação
— specs saem de ordem o tempo todo e isso é normal.

**Versão (opcional, mas recomendada):** amarre a versão do artefato ao número da spec —
`0.<NNN>.<correção>`. O ganho é operacional: `/status`, `--version` e o header da resposta passam a
dizer **qual spec está no ar**, o que transforma "está deployado?" numa pergunta com resposta.
Duas regras que só aparecem depois de doer:

- **A versão nunca anda para trás.** Spec implementada fora de ordem (a 104 depois da 110)
  incrementa o último dígito da versão corrente (`0.110.1`), não usa o próprio número. Qual spec
  entrou fica registrado no índice e na mensagem do commit — que é onde se procura isso.
- Se a linguagem tiver instalação editável/cache de metadata, **reinstale depois do bump**, senão o
  processo continua reportando a versão anterior.

## Casos especiais que o padrão precisa cobrir

**Achados de code review.** Viram uma spec própria ("correções pós-review"), com um CA por achado.
Assim o achado ganha número, teste e rastro — em vez de virar TODO que ninguém executa. Vale
registrar na spec **de qual review veio** e a data.

**Dívida técnica.** Dívida vira spec **com data e régua de remoção**. Alias de compatibilidade sem
data de remoção vira patrimônio: quem chega novo lê as duas formas na documentação e conclui que
ambas são legítimas. A spec da remoção deve dizer **como medir** que ninguém usa mais (log de
auditoria, telemetria) antes de remover — e separar o que **nunca** sai (leitores de dado já
gravado em disco costumam ser compatibilidade permanente, não dívida).

**Spec descartada.** Se a feature é abandonada, marque `descartada` com o motivo (custo, prioridade,
alternativa escolhida) e **mantenha o arquivo**. O número não é reaproveitado. Spec descartada com
motivo escrito é o que impede a mesma ideia de voltar a cada trimestre.

**CA reprovado na verificação.** Se um critério não passou e foi revertido, registre no status e
nas notas da spec. Índice com "Implementada (CA-18 revertida)" é informação; índice com
"Implementada" mentindo é armadilha.

**Mudança de comportamento já especificado.** Não reescreva a spec antiga em silêncio. Ou edite
declarando a mudança, ou escreva uma spec nova que **supersede** a anterior e diga isso nas duas.

## SDD com ou sem TDD

São ortogonais. A spec antes do código é inegociável; o ciclo red→green é uma escolha de projeto:

- **SDD + TDD estrito:** cada CA vira teste que falha antes da implementação. Custo alto, cabe bem
  em núcleo de domínio e nas primeiras dezenas de specs, enquanto os contratos assentam.
- **SDD sem TDD estrito:** testes escritos junto ou depois, obrigatórios só para CAs que tocam
  invariantes e contratos públicos; o resto é verificação manual documentada no PR.

Um projeto pode transicionar do primeiro para o segundo — registre **em qual spec** a transição
aconteceu, no `specs/README.md`, senão o histórico fica incoerente para quem lê de trás para frente.

## Antipadrões

- **Spec escrita depois do código, para justificá-lo.** Dá para reconhecer: os CAs descrevem a
  implementação ("a função `X` recebe o parâmetro `Y`") em vez do comportamento.
- **CA que é passo de implementação.** "Criar o módulo `foo.py`" não é critério de aceite — nada
  nele pode ser reprovado sem olhar a estrutura de arquivos.
- **Fora de escopo vazio.**
- **Índice desatualizado.** Assim que o índice para de refletir a realidade, ele deixa de ser
  consultado, e o padrão morre em uma semana.
- **Spec gigante.** Mais de ~20 CAs, ou objetivo com dois "e" — divida. Duas specs pequenas se
  implementam, revisam e revertem; uma grande fica meio pronta.
- **Invariantes tocadas em branco por padrão.** Se o projeto tem invariantes declaradas, a seção
  ou lista quais são tocadas, ou afirma explicitamente que nenhuma é.

## Checklist antes do merge

- [ ] Spec existe, numerada, a partir do template, e está no índice.
- [ ] Objetivo é o problema; contexto tem evidência verificável.
- [ ] Todo `CA-n` é reprovável — nenhum é desejo ou passo de implementação.
- [ ] Há CA de não-regressão e CA de caso de erro.
- [ ] Fora de escopo preenchido, com motivo.
- [ ] Invariantes tocadas listadas (ou negadas explicitamente).
- [ ] CAs de invariante/contrato têm teste automatizado citando o número no nome.
- [ ] Seção *Testes que cobrem esta spec* mapeia arquivo → CAs.
- [ ] CAs verificados manualmente estão documentados no PR.
- [ ] Status atualizado; versão bumpada; documentação de comportamento visível (rota, flag, tela)
      atualizada **no mesmo PR**.

## Arquivos desta skill

- `install.sh` — instalador idempotente do padrão num projeto (ver acima).
- `templates/_template.md` — template de spec, pronto para copiar em `specs/`.
- `templates/README-specs.md` — `specs/README.md` inicial, com tokens que o instalador substitui.
- `referencias/exemplos-de-ca.md` — CAs reais, bons e ruins, comentados; e o esqueleto do teste de
  contrato correspondente.
