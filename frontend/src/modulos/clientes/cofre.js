import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { cofreApi } from './api'

/**
 * O cofre das senhas nesta aba. O token que o servidor devolve ao abrir fica só aqui, na memória (nunca no
 * localStorage): recarregar a página ou fechar a aba fecha o cofre. Quem precisa de uma senha chama
 * {@link garantirAberto}: se o cofre estiver fechado, o diálogo pede o código do celular e a promessa só resolve
 * depois de aberto (ou rejeita, se a pessoa cancelar).
 */
export const useCofreStore = defineStore('cofreDosClientes', () => {
  const token = ref(null)
  const abertoAte = ref(null)
  const estado = ref(null)
  const pedindo = ref(false)
  let pendentes = []
  let relogio = null

  const aberto = computed(() => !!token.value && !!abertoAte.value && new Date(abertoAte.value) > new Date())

  async function carregar() {
    estado.value = await cofreApi.estado(token.value)
    if (!estado.value.aberto) esquecer()
    return estado.value
  }

  function guardar(abertura) {
    token.value = abertura.token
    abertoAte.value = abertura.abertoAte
    clearTimeout(relogio)
    relogio = setTimeout(esquecer, Math.max(0, new Date(abertura.abertoAte) - new Date()))
    pedindo.value = false
    const fila = pendentes
    pendentes = []
    fila.forEach((p) => p.resolver(token.value))
  }

  function esquecer() {
    token.value = null
    abertoAte.value = null
    clearTimeout(relogio)
  }

  async function abrir(codigo) {
    guardar(await cofreApi.abrir(codigo))
    await carregar()
  }

  async function ativar(codigo) {
    guardar(await cofreApi.ativar(codigo))
    await carregar()
  }

  async function fechar() {
    const t = token.value
    esquecer()
    if (t) await cofreApi.fechar(t).catch(() => {})
    await carregar().catch(() => {})
  }

  /** O token de um cofre aberto; fechado, pede o código antes. */
  function garantirAberto() {
    if (aberto.value) return Promise.resolve(token.value)
    pedindo.value = true
    return new Promise((resolver, rejeitar) => pendentes.push({ resolver, rejeitar }))
  }

  function cancelar() {
    pedindo.value = false
    const fila = pendentes
    pendentes = []
    fila.forEach((p) => p.rejeitar(new Error('cancelado')))
  }

  /** Uma chamada com o cofre: se o servidor disser que fechou (tempo, reinício), pede o código e tenta de novo. */
  async function comCofre(chamada) {
    const t = await garantirAberto()
    try {
      return await chamada(t)
    } catch (erro) {
      if (erro?.codigo !== 'COFRE_FECHADO') throw erro
      esquecer()
      return chamada(await garantirAberto())
    }
  }

  return { token, abertoAte, estado, pedindo, aberto, carregar, abrir, ativar, fechar, garantirAberto, cancelar, comCofre, esquecer }
})
