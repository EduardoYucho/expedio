<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { clientesApi, rotuloDoTipo } from '../api'

/** A lista dos clientes: uma busca só (nome, cidade, grupo, contato, telefone ou ID de acesso), por grupo. */
const modulos = useAcessoModulosStore()
const router = useRouter()

const busca = ref('')
const situacao = ref('ativos')
const lista = ref([])
const carregando = ref(false)
const erro = ref(null)
const grupos = ref([])

const novo = ref(null)
const salvando = ref(false)
const erroNovo = ref('')

let espera = null
let pedido = 0

async function carregar() {
  const meu = ++pedido
  carregando.value = true
  try {
    const resultado = await clientesApi.buscar(busca.value.trim() || undefined, situacao.value)
    if (meu === pedido) {
      lista.value = resultado
      erro.value = null
    }
  } catch (e) {
    if (meu === pedido) erro.value = e
  } finally {
    if (meu === pedido) carregando.value = false
  }
}

watch(busca, () => {
  clearTimeout(espera)
  espera = setTimeout(carregar, 250)
})
watch(situacao, carregar)

onMounted(() => {
  carregar()
  clientesApi.grupos().then((g) => (grupos.value = g)).catch(() => {})
})

/** A lista dividida por grupo (sem grupo por último). */
const porGrupo = computed(() => {
  const mapa = new Map()
  for (const c of lista.value) {
    const chave = c.grupo ?? ''
    if (!mapa.has(chave)) mapa.set(chave, [])
    mapa.get(chave).push(c)
  }
  return [...mapa.entries()].map(([grupo, clientes]) => ({ grupo, clientes }))
})

function abrirNovo() {
  novo.value = { nome: busca.value.trim(), grupo: '', cidade: '', documento: '', codigoChamados: '', observacao: '' }
  erroNovo.value = ''
}

async function criar() {
  salvando.value = true
  erroNovo.value = ''
  try {
    const { id } = await clientesApi.criar({ ...novo.value, ativo: true })
    novo.value = null
    router.push({ name: 'cliente', params: { id } })
  } catch (e) {
    erroNovo.value = mensagemDe(e)
  } finally {
    salvando.value = false
  }
}
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <header class="flex flex-wrap items-end gap-x-4 gap-y-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
      <div class="min-w-0 flex-1">
        <p class="rotulo">Ficha, acessos e contatos de cada cliente</p>
        <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">Clientes</h1>
      </div>
      <RouterLink v-if="modulos.administrarClientes" :to="{ name: 'cofre-dos-clientes' }" class="botao-secundario">Cofre</RouterLink>
      <button v-if="modulos.editarClientes" type="button" class="botao-primario" @click="abrirNovo">Novo cliente</button>
    </header>

    <div class="mt-5 flex flex-wrap items-center gap-3">
      <label class="min-w-[16rem] flex-1">
        <span class="sr-only">Buscar</span>
        <input
          v-model="busca"
          type="search"
          class="campo font-sans"
          placeholder="Nome, cidade, grupo, contato, telefone ou ID do AnyDesk…"
          autofocus
        />
      </label>
      <select v-model="situacao" class="campo w-auto font-sans text-sm" aria-label="Situação">
        <option value="ativos">Ativos</option>
        <option value="inativos">Inativos</option>
        <option value="todos">Todos</option>
      </select>
    </div>

    <!-- cliente novo -->
    <form v-if="novo" class="cartao mt-5 grid gap-3 px-5 py-4 sm:grid-cols-2" @submit.prevent="criar">
      <p class="rotulo sm:col-span-2">Cliente novo</p>
      <label class="sm:col-span-2">
        <span class="rotulo">Nome</span>
        <input v-model="novo.nome" class="campo mt-1 font-sans" maxlength="160" required placeholder="MH Modas - Urussanga" />
      </label>
      <label>
        <span class="rotulo">Grupo (rede de lojas)</span>
        <input v-model="novo.grupo" class="campo mt-1 font-sans" maxlength="120" list="grupos-de-clientes" placeholder="MH" />
      </label>
      <label>
        <span class="rotulo">Cidade</span>
        <input v-model="novo.cidade" class="campo mt-1 font-sans" maxlength="80" />
      </label>
      <label>
        <span class="rotulo">CNPJ / CPF</span>
        <input v-model="novo.documento" class="campo mt-1" maxlength="20" />
      </label>
      <label>
        <span class="rotulo">Código no sistema de chamados</span>
        <input v-model="novo.codigoChamados" class="campo mt-1" maxlength="20" placeholder="544" />
      </label>
      <p v-if="erroNovo" class="text-sm font-semibold text-carimbo sm:col-span-2" role="alert">{{ erroNovo }}</p>
      <div class="flex justify-end gap-2 sm:col-span-2">
        <button type="button" class="botao-secundario" @click="novo = null">Cancelar</button>
        <button type="submit" class="botao-primario" :disabled="salvando">{{ salvando ? 'Salvando…' : 'Criar e abrir a ficha' }}</button>
      </div>
      <datalist id="grupos-de-clientes">
        <option v-for="g in grupos" :key="g" :value="g" />
      </datalist>
    </form>

    <div class="mt-5">
      <EstadoDaTela
        :carregando="carregando && !lista.length"
        :erro="erro"
        :manter="lista.length > 0"
        :vazio="!lista.length"
        :vazio-texto="busca ? 'Nenhum cliente com esse termo.' : 'Nenhum cliente cadastrado ainda.'"
        carregando-texto="Buscando…"
        @tentar="carregar"
      >
        <section v-for="bloco in porGrupo" :key="bloco.grupo" class="mb-5">
          <p class="rotulo mb-2">{{ bloco.grupo || 'Sem grupo' }}</p>
          <ul class="cartao divide-y divide-linha">
            <li v-for="c in bloco.clientes" :key="c.id">
              <RouterLink
                :to="{ name: 'cliente', params: { id: c.id } }"
                class="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 transition hover:bg-papel-escuro"
              >
                <span class="min-w-0 flex-1">
                  <span class="block truncate font-semibold" :class="{ 'text-tinta-apagada line-through': !c.ativo }">{{ c.nome }}</span>
                  <span class="block text-xs text-tinta-suave">
                    {{ [c.cidade, c.codigoChamados && `chamados ${c.codigoChamados}`].filter(Boolean).join(' · ') || ' ' }}
                  </span>
                </span>
                <span
                  v-if="c.alertas"
                  class="rounded-[2px] border border-carimbo/40 bg-carimbo/5 px-1.5 py-0.5 text-[0.7rem] font-bold text-carimbo"
                  :title="`${c.alertas} alerta(s) na ficha`"
                >⚠ {{ c.alertas }}</span>
                <span class="flex flex-wrap gap-1">
                  <span v-for="t in c.tiposDeAcesso" :key="t" class="rounded-[2px] border border-linha px-1.5 py-0.5 text-[0.7rem] text-tinta-suave">
                    {{ rotuloDoTipo(t) }}
                  </span>
                </span>
              </RouterLink>
            </li>
          </ul>
        </section>
        <p v-if="lista.length >= 300" class="text-sm text-tinta-suave">Mostrando os 300 primeiros: refine a busca.</p>
      </EstadoDaTela>
    </div>
  </div>
</template>
