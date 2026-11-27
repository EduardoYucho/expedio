// O motivo de um erro em português, para a pessoa ler. Regra: o texto do navegador (em inglês, técnico) nunca vai
// para a tela. Só passa como está o erro escrito por nós para a pessoa (ErroClaro); os erros conhecidos do navegador
// viram o motivo; o resto vira a frase padrão, e o detalhe fica no console.

/** Um erro com a mensagem escrita para a pessoa (vai para a tela como está). */
export class ErroClaro extends Error {
  constructor(mensagem) {
    super(mensagem);
    this.name = 'ErroClaro';
    this.claro = true;
  }
}

const MOTIVOS = {
  NotReadableError: 'o arquivo está aberto em outro programa ou travado pelo Windows',
  NotAllowedError: 'o navegador não deu permissão para usar a pasta',
  SecurityError: 'o navegador não deu permissão para usar a pasta',
  NotFoundError: 'o arquivo ou a pasta foi apagado ou movido',
  QuotaExceededError: 'acabou o espaço no disco',
  NoModificationAllowedError: 'a pasta não aceita gravação (é somente leitura ou está em uso por outro programa)',
  InvalidModificationError: 'a pasta não aceita gravação (é somente leitura ou está em uso por outro programa)',
  InvalidStateError: 'o arquivo ou a pasta mudou durante a operação',
  TypeMismatchError: 'o item não é do tipo esperado (um arquivo no lugar de uma pasta)',
  AbortError: 'a operação foi cancelada',
  TimeoutError: 'o tempo esgotou',
  NetworkError: 'a conexão caiu',
  EncodingError: 'os dados não puderam ser lidos (o arquivo pode estar corrompido)',
  DataError: 'os dados não puderam ser lidos (o arquivo pode estar corrompido)',
  OperationError: 'a operação não pôde ser concluída pelo navegador',
};

/**
 * O motivo, em letra minúscula e sem ponto (para entrar numa frase: "Falhou: <motivo>.").
 *
 * @param padrao o que dizer quando o erro não é conhecido
 */
export function motivoDoErro(erro, padrao = 'um erro inesperado no navegador') {
  if (!erro) return padrao;
  if (erro.claro) return String(erro.message).replace(/\.$/, '');
  const motivo = MOTIVOS[erro.name];
  if (motivo) return motivo;
  const texto = String(erro.message || erro);
  if (erro.name === 'TypeError' && /fetch|network|load failed/i.test(texto)) return 'sem conexão com o sistema';
  if (/out of memory|allocation failed|Array buffer/i.test(texto)) {
    return 'faltou memória no navegador (feche outras abas e tente de novo)';
  }
  console.warn('Erro sem tradução para a tela:', erro);
  return padrao;
}
