---
spec: NNN
titulo: <título curto — o problema, não a solução>
status: rascunho | aprovada | implementada | descartada
atualizado: AAAA-MM-DD
---
# NNN — <Título>

## Objetivo
<Uma frase: que problema esta mudança resolve e para quem. Não descreva a implementação.>

## Contexto
<Por que agora. Traga EVIDÊNCIA, não impressão: reprodução literal do defeito, medição,
ponteiro de código (`arquivo.ext:linha`), relato de campo, restrição externa.

Se parte do caminho já está pronta, registre — evita que seja refeito.
Se há alternativa descartada, diga qual e por quê.>

## Comportamento esperado
<Prosa do que o sistema passa a fazer: entradas, saídas, efeitos colaterais, casos de borda.
Descreva o observável, não os arquivos que serão tocados.>

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** <afirmação verificável>
- **CA-2:** <afirmação verificável>
- **CA-3:** <caso de erro: entrada malformada / dependência fora / permissão ausente>
- **CA-4:** <não-regressão: sem a nova configuração, nada muda>

## Fora de escopo
<O que esta spec deliberadamente NÃO cobre — e por quê. Se houver spec candidata para
retomar o assunto, cite o número.>

## Invariantes tocadas
<Quais regras inegociáveis do projeto esta mudança encosta e como as preserva.
Se nenhuma, afirme explicitamente: "Nenhuma — isto é configuração de borda.">

## Testes que cobrem esta spec
<Preenchido no fechamento.>
- `<caminho/do/teste>` → CA-1, CA-2, CA-4
- CA-3 verificado manualmente: <como, e onde está registrado>

## Notas
<Opcional: CA revertido e por quê; correções aplicadas no code review; decisões que a
implementação precisou tomar e que a spec não previa.>
