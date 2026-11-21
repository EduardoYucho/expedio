/**
 * Copia um texto. Pela rede da Radmin (http) o navegador não libera a área de transferência moderna: aí vai pelo jeito
 * antigo (um campo escondido e o "copiar" do navegador).
 */
export async function copiar(texto) {
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(texto)
      return true
    } catch {
      // cai no jeito antigo
    }
  }
  const campo = document.createElement('textarea')
  campo.value = texto
  campo.setAttribute('readonly', '')
  campo.style.position = 'fixed'
  campo.style.opacity = '0'
  document.body.appendChild(campo)
  campo.select()
  let ok = false
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  } finally {
    campo.value = ''
    campo.remove()
  }
  return ok
}

/**
 * Depois de copiar uma senha: tenta esvaziar a área de transferência em alguns segundos. Só funciona onde o navegador
 * libera a área de transferência moderna (https ou o próprio computador do servidor); pelo http da rede, não dá.
 */
export function limparDepois(segundos = 30) {
  if (!(navigator.clipboard && window.isSecureContext)) return false
  setTimeout(() => {
    navigator.clipboard.writeText('').catch(() => {})
  }, segundos * 1000)
  return true
}
