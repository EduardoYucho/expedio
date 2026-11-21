<script setup>
import { onMounted, ref } from 'vue'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import { cofreApi } from '../api'
import { useCofreStore } from '../cofre'
import CofreDialogo from '../components/CofreDialogo.vue'

/**
 * O cofre dos clientes, para quem administra: a frase de recuperação (a única forma de abrir as senhas num computador
 * novo ou a partir de um backup), quem tem o código do celular (e zerar, se perder o celular) e o registro de uso.
 */
const cofre = useCofreStore()
const estado = ref(null)
const autenticadores = ref([])
const usos = ref([])
const carregando = ref(false)
const erro = ref(null)
const aviso = ref('')
const erroAcao = ref('')
const frase = ref('')
const confirmacao = ref('')
const ocupado = ref(false)

async function carregar() {
  carregando.value = true
  try {
    const [e, a, u] = await Promise.all([cofre.carregar(), cofreApi.autenticadores(), cofreApi.usos(undefined, 300)])
    estado.value = e
    autenticadores.value = a
    usos.value = u
    erro.value = null
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}
onMounted(carregar)

async function definirFrase() {
  erroAcao.value = ''
  if (frase.value !== confirmacao.value) {
    erroAcao.value = 'A confirmação não é igual à frase.'
    return
  }
  ocupado.value = true
  try {
    await cofre.comCofre((t) => cofreApi.definirFrase(t, frase.value))
    frase.value = ''
    confirmacao.value = ''
    aviso.value = 'Frase de recuperação definida. Guarde-a fora do computador (papel, cofre): sem ela, as senhas não voltam de um backup.'
    await carregar()
  } catch (e) {
    if (e?.message !== 'cancelado') erroAcao.value = mensagemDe(e)
  } finally {
    ocupado.value = false
  }
}

async function restaurar() {
  erroAcao.value = ''
  ocupado.value = true
  try {
    await cofreApi.restaurar(frase.value)
    frase.value = ''
    aviso.value = 'Chave do cofre restaurada: as senhas voltaram a abrir.'
    await carregar()
  } catch (e) {
    erroAcao.value = mensagemDe(e)
  } finally {
    ocupado.value = false
  }
}

async function zerar(a) {
  if (!confirm(`Zerar o código do celular de ${a.nome}? A pessoa vai ler um QR code novo para usar as senhas de novo.`)) return
  erroAcao.value = ''
  try {
    await cofreApi.zerarAutenticador(a.usuarioId)
    aviso.value = `Autenticador de ${a.nome} zerado.`
    await carregar()
  } catch (e) {
    erroAcao.value = mensagemDe(e)
  }
}

const dataHora = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—')
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <CofreDialogo />
    <header class="flex flex-wrap items-end gap-x-4 gap-y-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div class="min-w-0 flex-1">
        <p class="rotulo"><RouterLink :to="{ name: 'clientes' }" class="underline underline-offset-4">Clientes</RouterLink> · administração</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Cofre</h1>
      </div>
    </header>

    <p v-if="aviso" class="mt-4 text-sm font-semibold text-credito" role="status">{{ aviso }}</p>
    <p v-if="erroAcao" class="mt-4 text-sm font-semibold text-carimbo" role="alert">{{ erroAcao }}</p>

    <EstadoDaTela :carregando="carregando && !estado" :erro="erro" :manter="!!estado" @tentar="carregar">
      <template v-if="estado">
        <!-- a chave -->
        <section class="cartao mt-6 px-5 py-4">
          <p class="rotulo">A chave do cofre</p>
          <template v-if="estado.chave === 'TRANCADA'">
            <p class="mt-2 font-semibold text-carimbo">Trancado: a chave não está neste computador.</p>
            <p class="mt-1 text-sm text-tinta-suave">
              Acontece num computador novo ou depois de restaurar um backup. Se a cópia embrulhada voltou com o backup
              (<code>expedio restaurar</code>), digite a frase de recuperação.
            </p>
            <form class="mt-3 flex flex-wrap items-end gap-2" @submit.prevent="restaurar">
              <label class="min-w-[18rem] flex-1"><span class="rotulo">Frase de recuperação</span>
                <input v-model="frase" type="password" class="campo mt-1" autocomplete="off" required /></label>
              <button type="submit" class="botao-primario" :disabled="ocupado">{{ ocupado ? 'Conferindo… (leva uns segundos)' : 'Restaurar a chave' }}</button>
            </form>
          </template>
          <template v-else>
            <p class="mt-2 text-sm">
              <b>{{ estado.chave === 'NOVA' ? 'Ainda não criada' : 'Neste computador' }}</b>
              (nasce na primeira senha guardada).
              Frase de recuperação:
              <b :class="estado.recuperacaoDefinida ? 'text-credito' : 'text-carimbo'">{{ estado.recuperacaoDefinida ? 'definida' : 'não definida' }}</b>.
            </p>
            <p class="mt-1 text-sm text-tinta-suave">
              O backup leva só a chave embrulhada pela frase: um backup vazado não abre as senhas. Sem a frase, as senhas
              também não voltam num computador novo. Use 4 ou mais palavras que só você saiba e guarde fora do computador.
            </p>
            <form v-if="estado.chave === 'PRONTA'" class="mt-3 grid gap-2 sm:grid-cols-[1fr_1fr_auto] sm:items-end" @submit.prevent="definirFrase">
              <label><span class="rotulo">{{ estado.recuperacaoDefinida ? 'Frase nova' : 'Frase' }}</span>
                <input v-model="frase" type="password" class="campo mt-1" minlength="16" maxlength="300" autocomplete="new-password" required /></label>
              <label><span class="rotulo">Repita a frase</span>
                <input v-model="confirmacao" type="password" class="campo mt-1" autocomplete="new-password" required /></label>
              <button type="submit" class="botao-primario" :disabled="ocupado">{{ ocupado ? 'Guardando…' : 'Definir' }}</button>
            </form>
          </template>
        </section>

        <!-- autenticadores -->
        <section class="mt-6">
          <p class="rotulo mb-2">Quem tem o código do celular</p>
          <p v-if="!autenticadores.length" class="text-sm text-tinta-suave">Ninguém ativou ainda.</p>
          <ul v-else class="cartao divide-y divide-linha">
            <li v-for="a in autenticadores" :key="a.usuarioId" class="flex flex-wrap items-center gap-x-4 px-4 py-2 text-sm">
              <span class="font-semibold">{{ a.nome }}</span>
              <span class="text-tinta-suave">{{ a.login }}</span>
              <span class="text-xs text-tinta-apagada">{{ a.ativadoEm ? `ativado em ${dataHora(a.ativadoEm)}` : 'QR code gerado, não confirmado' }}</span>
              <button type="button" class="ml-auto text-xs text-carimbo underline underline-offset-4" @click="zerar(a)">zerar</button>
            </li>
          </ul>
        </section>

        <!-- uso -->
        <section class="mt-6">
          <p class="rotulo mb-2">Uso das senhas (os 300 mais recentes)</p>
          <p v-if="!usos.length" class="text-sm text-tinta-suave">Nada registrado ainda.</p>
          <div v-else class="cartao overflow-x-auto">
            <table class="w-full text-sm">
              <thead><tr class="text-left"><th class="rotulo px-3 py-2">Quando</th><th class="rotulo">Quem</th><th class="rotulo">O quê</th><th class="rotulo">Cliente</th><th class="rotulo">Detalhe</th><th class="rotulo">De onde</th></tr></thead>
              <tbody class="divide-y divide-linha">
                <tr v-for="u in usos" :key="u.id" :class="{ 'text-carimbo': u.acao === 'CODIGO_ERRADO' || u.acao === 'FRASE_ERRADA' }">
                  <td class="px-3 py-2 whitespace-nowrap">{{ dataHora(u.em) }}</td>
                  <td>{{ u.login }}</td>
                  <td class="font-semibold">{{ u.acao.toLowerCase().replace(/_/g, ' ') }}</td>
                  <td>
                    <RouterLink v-if="u.clienteId && u.cliente" :to="{ name: 'cliente', params: { id: u.clienteId } }" class="underline underline-offset-4">{{ u.cliente }}</RouterLink>
                  </td>
                  <td class="text-tinta-suave">{{ u.descricao }}</td>
                  <td class="carimbo text-xs">{{ u.ip }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </section>
      </template>
    </EstadoDaTela>
  </div>
</template>
