/**
 * O Plumb já se chamou "Conferência de Ponto" e "Expedio": o que o navegador guardou com um nome antigo (a sessão,
 * o menu recolhido, a versão da habilidade do Gemini) passa para o nome novo, sem ninguém precisar entrar de novo.
 * Roda antes de as telas lerem o armazenamento (main.js).
 */
const ANTIGOS = ['conferencia-ponto.', 'expedio.']
const NOVO = 'plumb.'

export function migrarArmazenamentoLocal() {
  try {
    for (const chave of Object.keys(localStorage)) {
      const antigo = ANTIGOS.find((prefixo) => chave.startsWith(prefixo))
      if (!antigo) continue
      const nova = NOVO + chave.slice(antigo.length)
      if (localStorage.getItem(nova) === null) localStorage.setItem(nova, localStorage.getItem(chave))
      localStorage.removeItem(chave)
    }
  } catch {
    // sem armazenamento (janela anônima, bloqueado): não há o que trazer
  }
}
