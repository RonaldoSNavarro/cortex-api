#!/usr/bin/env bash
# Instalador do padrão SDD (Spec Driven Development) num projeto.
#
# Idempotente: nunca sobrescreve arquivo existente — informa e segue.
# Uso:  bash install.sh [--target DIR] [--lang L] [--tdd strict|relaxed]
#                      [--version-bump|--no-version-bump] [--first-spec "Título"]
#                      [--agent-file CLAUDE.md|AGENTS.md|none] [--dry-run]
set -euo pipefail

SKILL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TPL="$SKILL_DIR/templates"

TARGET="$PWD"
LANG_ID=""
TDD="relaxed"
BUMP="ask"
FIRST_SPEC=""
AGENT_FILE=""
DRY=0

die()  { printf '\033[31merro:\033[0m %s\n' "$*" >&2; exit 1; }
info() { printf '  \033[32m%-9s\033[0m %s\n' "$1" "$2"; }
skip() { printf '  \033[33m%-9s\033[0m %s\n' "$1" "$2"; }

while [ $# -gt 0 ]; do
  case "$1" in
    --target)           TARGET="${2:?}"; shift 2 ;;
    --lang)             LANG_ID="${2:?}"; shift 2 ;;
    --tdd)              TDD="${2:?}"; shift 2 ;;
    --version-bump)     BUMP="yes"; shift ;;
    --no-version-bump)  BUMP="no"; shift ;;
    --first-spec)       FIRST_SPEC="${2:?}"; shift 2 ;;
    --agent-file)       AGENT_FILE="${2:?}"; shift 2 ;;
    --dry-run)          DRY=1; shift ;;
    -h|--help)          sed -n '2,10p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)                  die "opção desconhecida: $1" ;;
  esac
done

[ -d "$TARGET" ] || die "diretório alvo não existe: $TARGET"
TARGET="$(cd "$TARGET" && pwd)"
case "$TDD" in strict|relaxed) ;; *) die "--tdd aceita 'strict' ou 'relaxed'" ;; esac
[ "$BUMP" = "ask" ] && BUMP="no"

# ---------------------------------------------------------------- linguagem
detectar_lang() {
  local d="$1"
  [ -f "$d/pyproject.toml" ] || [ -f "$d/setup.py" ] || [ -f "$d/requirements.txt" ] && { echo python; return; }
  [ -f "$d/go.mod" ]        && { echo go; return; }
  [ -f "$d/package.json" ]  && { echo node; return; }
  [ -f "$d/Gemfile" ]       && { echo ruby; return; }
  [ -f "$d/pom.xml" ] || [ -f "$d/build.gradle" ] || [ -f "$d/build.gradle.kts" ] && { echo java; return; }
  ls "$d"/*.csproj "$d"/*.sln >/dev/null 2>&1 && { echo csharp; return; }
  echo generic
}
[ -n "$LANG_ID" ] || LANG_ID="$(detectar_lang "$TARGET")"

conv_block() {
  case "$1" in
    python) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `tests/contract/test_001_captura_de_evento.py`
- Cada caso: `test_ca<N>_descricao(...)`, com docstring citando a spec.
- Marcador: `pytestmark = pytest.mark.contract` — roda só a spec com `pytest -m contract -k 001`.
X
;;
    go) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `test/contract/spec001_captura_de_evento_test.go`
- Cada caso: `func TestCA4NomeDoComportamento(t *testing.T)`, com comentário citando a spec.
- Roda só a spec: `go test ./test/contract -run 'Spec001|CA[0-9]+'`.
X
;;
    node) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `tests/contract/001-captura-de-evento.test.ts`
- Cada caso: `test('CA-4: <afirmação do critério>', ...)` — o número no título do caso.
- Roda só a spec: `vitest run tests/contract/001-*` (ou o equivalente do runner do projeto).
X
;;
    ruby) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `spec/contract/001_captura_de_evento_spec.rb`
- Cada caso: `it 'CA-4: <afirmação do critério>' do ... end`.
- Roda só a spec: `rspec spec/contract/001_captura_de_evento_spec.rb`.
X
;;
    java) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `src/test/java/contract/Spec001CapturaDeEventoTest.java`
- Cada caso: `@Test void ca4_nomeDoComportamento()`, com Javadoc citando a spec.
- Roda só a spec: `mvn test -Dtest=Spec001CapturaDeEventoTest`.
X
;;
    csharp) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: `tests/Contract/Spec001CapturaDeEventoTests.cs`
- Cada caso: `[Fact] public void CA4_NomeDoComportamento()`.
- Roda só a spec: `dotnet test --filter Spec001CapturaDeEvento`.
X
;;
    *) cat <<'X'
- Spec: `specs/001-captura-de-evento.md`
- Teste de contrato: arquivo que espelha o número da spec (`.../contract/001-captura-de-evento.*`).
- Cada caso cita o critério no nome: `ca4_<comportamento>` ou `CA-4: <afirmação>`.
- Deve existir um jeito de rodar **só** os testes de uma spec (marcador, filtro ou diretório).
X
;;
  esac
}

tdd_block() {
  if [ "$TDD" = "strict" ]; then cat <<'X'
> **Regime de teste: SDD + TDD estrito.** Cada `CA-n` vira um teste que **falha antes** da
> implementação (red → green). Quando o projeto relaxar esse ciclo, registre aqui em qual spec
> a transição aconteceu — senão o histórico fica incoerente para quem lê de trás para frente.
X
  else cat <<'X'
> **Regime de teste: SDD sem TDD estrito.** A spec antes do código é inegociável; os testes são
> escritos junto ou depois da implementação. São **obrigatórios** para os CAs que tocam
> invariantes e contratos públicos; os demais podem ser verificados manualmente, com o registro
> no PR. Se o projeto mudar de regime, anote aqui em qual spec isso aconteceu.
X
  fi
}

bump_block() {
  [ "$BUMP" = "yes" ] || return 0
  cat <<'X'
6. **Bump de versão** — ao marcar a spec como implementada, suba a versão do artefato para
   `0.<NNN>.0`, onde `NNN` é o número da spec. É o que faz a versão publicada responder
   "qual spec está no ar". Duas regras: (a) **a versão nunca anda para trás** — spec implementada
   fora de ordem incrementa o último dígito da versão corrente (`0.110.1`) em vez de usar o
   próprio número; (b) correção posterior sobre a mesma spec incrementa o último dígito.
   Se a linguagem tiver instalação editável ou cache de metadata, **reinstale depois do bump**.
X
}

# --------------------------------------------------- substituição de tokens
render() { # render <arquivo-template> ; blocos vêm de variáveis de ambiente
  awk '
    /^\{\{BUMP_BLOCK\}\}$/  { if (length(ENVIRON["B_BUMP"])) print ENVIRON["B_BUMP"]; next }
    /^\{\{TDD_BLOCK\}\}$/   { print ENVIRON["B_TDD"];  next }
    /^\{\{CONV_BLOCK\}\}$/  { print ENVIRON["B_CONV"]; next }
    /^\{\{INDEX_ROWS\}\}$/  { if (length(ENVIRON["B_ROWS"])) print ENVIRON["B_ROWS"]; next }
    { print }
  ' "$1"
}

escrever() { # escrever <destino> <conteúdo-em-stdin>
  local dest="$1" rel="${1#$TARGET/}"
  if [ -e "$dest" ]; then cat >/dev/null; skip "existe" "$rel (mantido)"; return 0; fi
  if [ "$DRY" = "1" ]; then cat >/dev/null; info "criaria" "$rel"; return 0; fi
  mkdir -p "$(dirname "$dest")"; cat > "$dest"; info "criado" "$rel"
}

# ---------------------------------------------------------------- slugify
slugify() {
  printf '%s' "$1" \
    | iconv -f UTF-8 -t ASCII//TRANSLIT 2>/dev/null || printf '%s' "$1"
}
slug_de() {
  slugify "$1" | tr '[:upper:]' '[:lower:]' \
    | sed -e 's/[^a-z0-9]\+/-/g' -e 's/^-//' -e 's/-$//' | cut -c1-60
}

# ==========================================================================
printf '\n\033[1mSDD — instalando em %s\033[0m\n' "$TARGET"
printf '  linguagem: %s | regime: %s | bump de versão: %s%s\n\n' \
  "$LANG_ID" "$TDD" "$BUMP" "$([ "$DRY" = 1 ] && printf ' | \033[33mDRY-RUN\033[0m')"

B_CONV="$(conv_block "$LANG_ID")"; export B_CONV
B_TDD="$(tdd_block)";              export B_TDD
B_BUMP="$(bump_block)";            export B_BUMP

# primeira spec (opcional) — precisa da linha do índice
HOJE="$(date +%F)"

# Numeração NUNCA é reaproveitada: o instalador só cria a 001 num projeto sem specs.
MAIOR=0
for f in "$TARGET"/specs/[0-9][0-9][0-9]-*.md; do
  [ -e "$f" ] || continue
  n="$(basename "$f" | cut -c1-3)"
  [ "$((10#$n))" -gt "$MAIOR" ] && MAIOR="$((10#$n))"
done
if [ -n "$FIRST_SPEC" ] && [ "$MAIOR" -gt 0 ]; then
  PROXIMA="$(printf '%03d' "$((MAIOR + 1))")"
  skip "ignorado" "--first-spec: já existe spec $(printf '%03d' "$MAIOR") — a próxima livre é $PROXIMA"
  skip "" "        numeração nunca é reaproveitada; crie a $PROXIMA pelo fluxo normal"
  FIRST_SPEC=""
fi

if [ -n "$FIRST_SPEC" ]; then
  SLUG="$(slug_de "$FIRST_SPEC")"
  [ -n "$SLUG" ] || die "não consegui derivar um slug de: $FIRST_SPEC"
  B_ROWS="| 001 | $FIRST_SPEC | Rascunho | — |"
else
  B_ROWS="| 001 | <título da primeira spec> | Rascunho | — |"
fi
export B_ROWS

render "$TPL/README-specs.md" | escrever "$TARGET/specs/README.md"
cat "$TPL/_template.md"       | escrever "$TARGET/specs/_template.md"

if [ -n "$FIRST_SPEC" ]; then
  sed -e "s/^spec: NNN$/spec: 001/" \
      -e "s/^titulo: .*$/titulo: $FIRST_SPEC/" \
      -e "s/^status: .*$/status: rascunho/" \
      -e "s/^atualizado: .*$/atualizado: $HOJE/" \
      -e "s/^# NNN — <Título>$/# 001 — $FIRST_SPEC/" \
      "$TPL/_template.md" | escrever "$TARGET/specs/001-$SLUG.md"
fi

# ------------------------------------------------- fisgar o arquivo do agente
if [ "$AGENT_FILE" != "none" ]; then
  if [ -z "$AGENT_FILE" ]; then
    if   [ -f "$TARGET/CLAUDE.md" ]; then AGENT_FILE="CLAUDE.md"
    elif [ -f "$TARGET/AGENTS.md" ]; then AGENT_FILE="AGENTS.md"
    else AGENT_FILE="CLAUDE.md"; fi
  fi
  ALVO_AGENTE="$TARGET/$AGENT_FILE"
  if [ -f "$ALVO_AGENTE" ] && grep -q 'specs/README.md' "$ALVO_AGENTE"; then
    skip "existe" "$AGENT_FILE já aponta para specs/README.md"
  else
    SECAO=$(cat <<'X'

## Fluxo de desenvolvimento — SDD

Este projeto segue **Spec Driven Development**: toda mudança de comportamento nasce de uma spec
em `specs/NNN-nome.md`, **antes** do código. As regras, a convenção de nomes e o **índice de
specs** (que é o changelog do projeto) estão em `specs/README.md` — leia-o antes de implementar.

- **Sem spec, sem código.** Se o pedido muda o que o sistema faz, o primeiro entregável é a spec.
- Critérios de aceite (`CA-n`) são afirmações **verificáveis**; testes citam o número no nome.
- Spec e implementação são revisadas no **mesmo PR**.
X
)
    if [ "$DRY" = "1" ]; then
      info "acresc." "$AGENT_FILE (seção 'Fluxo de desenvolvimento — SDD')"
    else
      printf '%s\n' "$SECAO" >> "$ALVO_AGENTE"
      info "acresc." "$AGENT_FILE (seção 'Fluxo de desenvolvimento — SDD')"
    fi
  fi
fi

printf '\n\033[1mPróximo passo\033[0m\n'
if [ -n "$FIRST_SPEC" ]; then
  printf '  Preencher specs/001-%s.md — objetivo, contexto COM evidência e os CA-n.\n\n' "$SLUG"
else
  N="$(printf '%03d' "$((MAIOR + 1))")"
  printf '  Criar a próxima spec: cp specs/_template.md specs/%s-<slug>.md\n' "$N"
  printf '  Depois, registrá-la no índice de specs/README.md.\n\n'
fi
