# Specs — Spec Driven Development (SDD)

Cada mudança de comportamento deste projeto nasce de uma **spec leve** aqui, **antes** do código.
A spec é a fonte dos **critérios de aceite** (`CA-n`), que orientam a implementação e a verificação.

Este arquivo é, além das regras, o **índice** — e o índice é o changelog do projeto.

## Fluxo

1. **Spec** — crie `specs/NNN-nome-da-feature.md` a partir do `_template.md`, com objetivo,
   contexto (com evidência), comportamento esperado e **critérios de aceite numerados**.
2. **Índice** — registre a spec na tabela abaixo. Spec fora do índice é spec que ninguém acha.
3. **Código** — implemente o comportamento descrito. Comportamento não previsto que aparecer
   durante a implementação volta para a spec, antes do merge.
4. **Verificação** — cada `CA-n` é verificado antes do merge: teste automatizado quando o critério
   toca invariante ou contrato público; verificação manual **documentada no PR** quando não valer
   o custo do teste.
5. **Fechamento** — status → `implementada`, seção *Testes que cobrem esta spec* preenchida,
   índice atualizado.
{{BUMP_BLOCK}}

## Regras

- **Sem spec, sem código.** Mudança de comportamento começa por editar ou criar a spec.
- Critérios de aceite são **verificáveis** — afirmações que alguém pode reprovar, não desejos.
- CAs que tocam **invariantes** do projeto exigem teste automatizado; os demais podem ser
  verificados manualmente, desde que o PR registre como.
- Specs são versionadas com o código e revisadas **no mesmo PR** da implementação.
- Numeração é sequencial e **nunca reutilizada** — nem quando a spec é descartada.
- Toda spec tem *Fora de escopo* preenchido.

{{TDD_BLOCK}}

## Convenção de nomes

{{CONV_BLOCK}}
- O cabeçalho do arquivo de teste lista, em uma linha cada, todos os CAs que ele cobre.
- Um CA pode ter mais de um teste (os dois sentidos de uma precedência, por exemplo); todos citam
  o mesmo número.

## Status

`rascunho` → `aprovada` → `implementada`. Use estados honestos quando for o caso:
`implementada (CA-N revertida)`, `descartada` (com motivo, arquivo mantido, número não reaproveitado).

## Índice de specs

| Spec | Título | Status | Testes |
|---|---|---|---|
{{INDEX_ROWS}}
