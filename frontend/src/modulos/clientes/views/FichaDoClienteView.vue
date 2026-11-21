<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import EstadoDaTela from '@/components/EstadoDaTela.vue'
import { mensagemDe } from '@/utils/erros'
import { salvarBlob } from '@/utils/download'
import { useAcessoModulosStore } from '@/modulos/acessoAosModulos'
import { clientesApi, cofreApi, PAPEIS_DA_MAQUINA, TIPOS_DE_ACESSO, rotuloDoPapel, rotuloDoTipo } from '../api'
import { useCofreStore } from '../cofre'
import { copiar, limparDepois } from '../copiar'
import { pareceTerSenha, sugerirAcessos } from '../sugerir'
import CofreDialogo from '../components/CofreDialogo.vue'

/**
 * A ficha de um cliente: alertas em destaque, acessos (a senha só pelo cofre), máquinas, contatos, anotações e, para
 * quem administra, quem usou as senhas.
 */
const props = defineProps({ id: { type: String, required: true } })
const modulos = useAcessoModulosStore()
const cofre = useCofreStore()
const router = useRouter()

const ficha = ref(null)
const carregando = ref(false)
const erro = ref(null)
const aba = ref('acessos')
const aviso = ref('')
const erroAcao = ref('')
const grupos = ref([])
let avisoTimer = null

/** Senhas à mostra: id do acesso → senha (somem sozinhas em 30 s). */
const visiveis = reactive({})
const timers = new Map()

const editarCliente = ref(null)
const editando = ref(null) // { tipo, id|null, dados }
const novaAnotacao = ref('')
const usos = ref(null)
const colando = ref(null) // { texto, sugestoes }

const c = computed(() => ficha.value?.cliente)
const alertasAtivos = computed(() => (ficha.value?.alertas ?? []).filter((a) => a.ativo))
const maquinaPorId = computed(() => Object.fromEntries((ficha.value?.maquinas ?? []).map((m) => [m.id, m])))
const abas = computed(() => [
  { id: 'acessos', rotulo: 'Acessos', qtd: ficha.value?.acessos.length },
  { id: 'maquinas', rotulo: 'Máquinas', qtd: ficha.value?.maquinas.length },
  { id: 'contatos', rotulo: 'Contatos', qtd: ficha.value?.contatos.length },
  { id: 'anotacoes', rotulo: 'Anotações', qtd: ficha.value?.anotacoes.length },
  { id: 'alertas', rotulo: 'Alertas', qtd: ficha.value?.alertas.length },
  ...(modulos.administrarClientes ? [{ id: 'usos', rotulo: 'Uso das senhas' }] : []),
])

async function carregar() {
  carregando.value = true
  try {
    ficha.value = await clientesApi.ficha(props.id)
    erro.value = null
  } catch (e) {
    erro.value = e
  } finally {
    carregando.value = false
  }
}

onMounted(() => {
  carregar()
  cofre.carregar().catch(() => {})
})
onBeforeUnmount(() => {
  timers.forEach((t) => clearTimeout(t))
  clearTimeout(avisoTimer)
})

function avisar(texto) {
  aviso.value = texto
  clearTimeout(avisoTimer)
  avisoTimer = setTimeout(() => (aviso.value = ''), 4000)
}

/** Senha escrita num texto comum fica à vista de todos: só passa se a pessoa confirmar. */
function semSenhaNoTexto(...textos) {
  if (!textos.some(pareceTerSenha)) return true
  return confirm('Este texto parece ter uma senha. Senhas devem ir no campo Senha de um acesso (ficam no cofre); '
    + 'aqui ficam à vista de todos que veem a ficha. Salvar mesmo assim?')
}

async function acao(fn, sucesso) {
  erroAcao.value = ''
  try {
    await fn()
    if (sucesso) avisar(sucesso)
    return true
  } catch (e) {
    if (e?.message !== 'cancelado') erroAcao.value = mensagemDe(e)
    return false
  }
}

// ---------------------------------------------------------------------------------------- cliente

function abrirEdicaoDoCliente() {
  const x = c.value
  editarCliente.value = { nome: x.nome, grupo: x.grupo ?? '', documento: x.documento ?? '', cidade: x.cidade ?? '',
    ativo: x.ativo, codigoChamados: x.codigoChamados ?? '', observacao: x.observacao ?? '' }
  clientesApi.grupos().then((g) => (grupos.value = g)).catch(() => {})
}

async function salvarCliente() {
  if (!semSenhaNoTexto(editarCliente.value.observacao)) return
  if (await acao(() => clientesApi.alterar(props.id, editarCliente.value), 'Cliente salvo.')) {
    editarCliente.value = null
    await carregar()
  }
}

async function apagarCliente() {
  if (!confirm(`Apagar "${c.value.nome}" e tudo da ficha (acessos e senhas inclusive)? Não dá para desfazer.`)) return
  if (await acao(() => clientesApi.apagar(props.id))) router.replace({ name: 'clientes' })
}

// ---------------------------------------------------------------------------------------- itens da ficha

const VAZIOS = {
  alertas: () => ({ texto: '', ativo: true }),
  contatos: () => ({ nome: '', funcao: '', telefone: '', email: '', autoriza: false, observacao: '' }),
  maquinas: () => ({ nome: '', papel: 'SERVIDOR', pastaSistema: '', observacao: '' }),
  acessos: () => ({ tipo: 'ANYDESK', identificador: '', porta: null, usuario: '', observacao: '', maquinaId: null, senha: '' }),
}

function novoItem(tipo) {
  editando.value = { tipo, id: null, dados: VAZIOS[tipo]() }
}

function editarItem(tipo, item) {
  const dados = { ...VAZIOS[tipo]() }
  for (const k of Object.keys(dados)) if (k in item) dados[k] = item[k] ?? dados[k]
  if (tipo === 'acessos') dados.senha = ''
  editando.value = { tipo, id: item.id, dados }
}

async function salvarItem() {
  const { tipo, id, dados } = editando.value
  const { senha, ...semSenha } = dados
  if (!semSenhaNoTexto(semSenha.observacao, semSenha.texto)) return
  const corpo = { ...semSenha, porta: semSenha.porta ? Number(semSenha.porta) : null }
  const ok = await acao(async () => {
    let itemId = id
    if (id) await clientesApi.alterarItem(props.id, tipo, id, corpo)
    else itemId = (await clientesApi.criarItem(props.id, tipo, corpo)).id
    if (tipo === 'acessos' && senha) {
      await cofre.comCofre((t) => cofreApi.gravarSenha(t, itemId, senha))
    }
  }, 'Salvo.')
  if (ok) {
    editando.value = null
    await carregar()
  }
}

async function apagarItem(tipo, item, descricao) {
  if (!confirm(`Apagar ${descricao}?`)) return
  if (await acao(() => clientesApi.apagarItem(props.id, tipo, item.id), 'Apagado.')) await carregar()
}

async function alternarAlerta(a) {
  if (await acao(() => clientesApi.alterarItem(props.id, 'alertas', a.id, { texto: a.texto, ativo: !a.ativo }))) await carregar()
}

async function anotar() {
  const texto = novaAnotacao.value.trim()
  if (!texto || !semSenhaNoTexto(texto)) return
  if (await acao(() => clientesApi.criarItem(props.id, 'anotacoes', { texto }), 'Anotação guardada.')) {
    novaAnotacao.value = ''
    await carregar()
  }
}

// ---------------------------------------------------------------------------------------- senhas

async function mostrarSenha(acesso) {
  if (visiveis[acesso.id]) {
    esconder(acesso.id)
    return
  }
  await acao(async () => {
    const { senha } = await cofre.comCofre((t) => cofreApi.senha(t, acesso.id, 'MOSTRAR'))
    visiveis[acesso.id] = senha
    clearTimeout(timers.get(acesso.id))
    timers.set(acesso.id, setTimeout(() => esconder(acesso.id), 30_000))
  })
}

function esconder(id) {
  delete visiveis[id]
  clearTimeout(timers.get(id))
  timers.delete(id)
}

async function copiarSenha(acesso) {
  let copiada = true
  await acao(async () => {
    const { senha } = await cofre.comCofre((t) => cofreApi.senha(t, acesso.id, 'COPIAR'))
    copiada = await copiar(senha)
    if (copiada) avisar(limparDepois(30) ? 'Senha copiada (sai da área de transferência em 30 s).' : 'Senha copiada.')
  })
  if (!copiada) erroAcao.value = 'O navegador não deixou copiar. Use "mostrar" e copie à mão.'
}

async function apagarSenha(acesso) {
  if (!confirm('Apagar a senha guardada deste acesso?')) return
  if (await acao(() => cofre.comCofre((t) => cofreApi.gravarSenha(t, acesso.id, '')), 'Senha apagada.')) await carregar()
}

async function copiarTexto(texto, rotulo) {
  if (await copiar(texto)) avisar(`${rotulo} copiado.`)
  else erroAcao.value = 'O navegador não deixou copiar.'
}

const idSemEspacos = (a) => a.identificador.replace(/\s/g, '')

/** Arquivo .rdp com o endereço e a porta (a senha nunca vai no arquivo). */
function baixarRdp(acesso) {
  const endereco = acesso.porta ? `${acesso.identificador}:${acesso.porta}` : acesso.identificador
  const linhas = [`full address:s:${endereco}`, 'prompt for credentials:i:1', 'administrative session:i:0']
  if (acesso.usuario) linhas.push(`username:s:${acesso.usuario}`)
  const nome = `${c.value.nome} - ${acesso.identificador}`.replace(/[\\/:*?"<>|]+/g, '_').slice(0, 80)
  salvarBlob(new Blob([linhas.join('\r\n') + '\r\n'], { type: 'application/x-rdp' }), `${nome}.rdp`)
}

// ---------------------------------------------------------------------------------------- colar e sugerir

function abrirColar() {
  colando.value = { texto: '', sugestoes: [] }
}

function sugerir() {
  colando.value.sugestoes = sugerirAcessos(colando.value.texto).map((s) => ({ ...s, marcado: true }))
  if (!colando.value.sugestoes.length) erroAcao.value = 'Nenhuma linha parecida com acesso (AnyDesk, UltraViewer, Radmin, IP).'
}

async function adicionarSugestoes() {
  const marcadas = colando.value.sugestoes.filter((s) => s.marcado && s.identificador)
  const ok = await acao(async () => {
    for (const s of marcadas) {
      const { id } = await clientesApi.criarItem(props.id, 'acessos', {
        tipo: s.tipo, identificador: s.identificador, porta: s.porta ? Number(s.porta) : null,
        usuario: s.usuario || null, observacao: s.observacao,
      })
      if (s.senha && modulos.senhasDosClientes) await cofre.comCofre((t) => cofreApi.gravarSenha(t, id, s.senha))
    }
  }, `${marcadas.length} acesso(s) adicionado(s).`)
  if (ok) colando.value = null
  await carregar()
}

// ---------------------------------------------------------------------------------------- uso das senhas e backup

async function verUsos() {
  aba.value = 'usos'
  await acao(async () => (usos.value = await cofreApi.usos(props.id, 300)))
}

function novaColeta() {
  const servidor = (ficha.value.maquinas ?? []).find((m) => m.pastaSistema)
  router.push({ name: 'backups-clientes', query: { cliente: c.value.nome, pasta: servidor?.pastaSistema ?? '' } })
}

const dataHora = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—')
</script>

<template>
  <div class="mx-auto max-w-6xl px-4 pb-16 sm:px-6">
    <CofreDialogo />

    <EstadoDaTela :carregando="carregando && !ficha" :erro="erro" :manter="!!ficha" carregando-texto="Abrindo a ficha…" @tentar="carregar">
      <template v-if="ficha">
        <header class="flex flex-wrap items-end gap-x-4 gap-y-3 border-b-2 border-tinta pt-6 pb-4 sm:pt-8">
          <div class="min-w-0 flex-1">
            <p class="rotulo">
              <RouterLink :to="{ name: 'clientes' }" class="underline underline-offset-4">Clientes</RouterLink>
              <template v-if="c.grupo"> · {{ c.grupo }}</template>
              <template v-if="c.codigoChamados"> · chamados {{ c.codigoChamados }}</template>
            </p>
            <h1 class="mt-1 font-sans text-3xl leading-none font-extrabold tracking-tight [font-stretch:80%] sm:text-4xl">
              {{ c.nome }}
              <span v-if="!c.ativo" class="ml-2 align-middle text-sm font-bold tracking-wider text-carimbo uppercase">inativo</span>
            </h1>
            <p class="mt-1 text-sm text-tinta-suave">{{ [c.cidade, c.documento].filter(Boolean).join(' · ') }}</p>
          </div>
          <button v-if="modulos.backup" type="button" class="botao-secundario" @click="novaColeta">Nova coleta de backup</button>
          <button v-if="modulos.editarClientes" type="button" class="botao-secundario" @click="abrirEdicaoDoCliente">Editar</button>
          <span v-if="cofre.aberto" class="flex items-center gap-2 text-xs font-semibold text-credito">
            Cofre aberto
            <button type="button" class="botao-secundario py-1! text-xs" @click="cofre.fechar()">Fechar</button>
          </span>
        </header>

        <!-- alertas em destaque -->
        <div v-for="a in alertasAtivos" :key="a.id" class="mt-4 rounded-[3px] border-2 border-carimbo bg-carimbo/5 px-4 py-3 font-semibold text-carimbo" role="alert">
          ⚠ {{ a.texto }}
        </div>
        <p v-if="c.observacao" class="cartao mt-4 px-4 py-3 text-sm whitespace-pre-line">{{ c.observacao }}</p>

        <p v-if="aviso" class="mt-3 text-sm font-semibold text-credito" role="status">{{ aviso }}</p>
        <p v-if="erroAcao" class="mt-3 text-sm font-semibold text-carimbo" role="alert">{{ erroAcao }}</p>

        <!-- editar o cliente -->
        <form v-if="editarCliente" class="cartao mt-4 grid gap-3 px-5 py-4 sm:grid-cols-2" @submit.prevent="salvarCliente">
          <label class="sm:col-span-2"><span class="rotulo">Nome</span>
            <input v-model="editarCliente.nome" class="campo mt-1 font-sans" maxlength="160" required /></label>
          <label><span class="rotulo">Grupo</span>
            <input v-model="editarCliente.grupo" class="campo mt-1 font-sans" maxlength="120" list="grupos-da-ficha" /></label>
          <label><span class="rotulo">Cidade</span>
            <input v-model="editarCliente.cidade" class="campo mt-1 font-sans" maxlength="80" /></label>
          <label><span class="rotulo">CNPJ / CPF</span>
            <input v-model="editarCliente.documento" class="campo mt-1" maxlength="20" /></label>
          <label><span class="rotulo">Código no sistema de chamados</span>
            <input v-model="editarCliente.codigoChamados" class="campo mt-1" maxlength="20" /></label>
          <label class="sm:col-span-2"><span class="rotulo">Observação</span>
            <textarea v-model="editarCliente.observacao" class="campo mt-1 font-sans" rows="3" maxlength="4000" /></label>
          <label class="flex items-center gap-2 text-sm"><input v-model="editarCliente.ativo" type="checkbox" /> Cliente ativo</label>
          <div class="flex flex-wrap justify-end gap-2 sm:col-span-2">
            <button v-if="modulos.administrarClientes" type="button" class="botao-secundario mr-auto text-carimbo!" @click="apagarCliente">Apagar cliente</button>
            <button type="button" class="botao-secundario" @click="editarCliente = null">Cancelar</button>
            <button type="submit" class="botao-primario">Salvar</button>
          </div>
          <datalist id="grupos-da-ficha"><option v-for="g in grupos" :key="g" :value="g" /></datalist>
        </form>

        <!-- abas -->
        <nav class="mt-6 flex flex-wrap gap-1 border-b border-linha" aria-label="Partes da ficha">
          <button
            v-for="x in abas"
            :key="x.id"
            type="button"
            class="-mb-px rounded-t-[3px] border border-b-0 px-3 py-2 text-sm font-semibold"
            :class="aba === x.id ? 'border-linha bg-cartao' : 'border-transparent text-tinta-suave hover:text-tinta'"
            :aria-current="aba === x.id ? 'page' : undefined"
            @click="x.id === 'usos' ? verUsos() : (aba = x.id)"
          >
            {{ x.rotulo }}<span v-if="x.qtd" class="ml-1 text-xs text-tinta-suave">{{ x.qtd }}</span>
          </button>
        </nav>

        <!-- ACESSOS -->
        <section v-if="aba === 'acessos'" class="mt-4">
          <div v-if="modulos.editarClientes" class="mb-3 flex flex-wrap gap-2">
            <button type="button" class="botao-secundario" @click="novoItem('acessos')">Novo acesso</button>
            <button type="button" class="botao-secundario" @click="abrirColar">Colar do sistema de chamados</button>
          </div>

          <!-- colar e sugerir -->
          <div v-if="colando" class="cartao mb-4 px-5 py-4">
            <p class="rotulo">Colar e sugerir</p>
            <p class="mt-1 text-sm text-tinta-suave">Cole as observações do sistema de chamados: as linhas com AnyDesk, UltraViewer, Radmin ou IP viram sugestões. Confira antes de adicionar.</p>
            <textarea v-model="colando.texto" class="campo mt-2 font-sans text-sm" rows="6" />
            <div class="mt-2 flex gap-2">
              <button type="button" class="botao-secundario" @click="sugerir">Sugerir</button>
              <button type="button" class="botao-secundario" @click="colando = null">Fechar</button>
            </div>
            <table v-if="colando.sugestoes.length" class="mt-4 w-full text-sm">
              <thead><tr class="text-left"><th class="rotulo py-1"></th><th class="rotulo">Tipo</th><th class="rotulo">ID / endereço</th><th class="rotulo">Porta</th><th class="rotulo">Usuário</th><th class="rotulo">Senha</th></tr></thead>
              <tbody>
                <tr v-for="(s, i) in colando.sugestoes" :key="i" class="align-top">
                  <td class="py-1 pr-2"><input v-model="s.marcado" type="checkbox" :aria-label="`Adicionar ${s.identificador}`" /></td>
                  <td class="pr-2"><select v-model="s.tipo" class="campo py-1! font-sans text-sm"><option v-for="t in TIPOS_DE_ACESSO" :key="t.valor" :value="t.valor">{{ t.rotulo }}</option></select></td>
                  <td class="pr-2"><input v-model="s.identificador" class="campo py-1! text-sm" /></td>
                  <td class="pr-2"><input v-model="s.porta" class="campo w-24 py-1! text-sm" inputmode="numeric" /></td>
                  <td class="pr-2"><input v-model="s.usuario" class="campo py-1! text-sm" /></td>
                  <td>
                    <input v-model="s.senha" class="campo py-1! text-sm" type="password" autocomplete="off" :disabled="!modulos.senhasDosClientes" :placeholder="modulos.senhasDosClientes ? '' : 'sem permissão'" />
                    <p v-if="s.outrasSenhas" class="mt-1 text-xs font-semibold text-carimbo">
                      A linha tem mais {{ s.outrasSenhas }} senha(s): só a primeira vai para este acesso. Cadastre a outra num acesso próprio (ex.: o usuário do Windows).
                    </p>
                  </td>
                </tr>
              </tbody>
            </table>
            <button v-if="colando.sugestoes.length" type="button" class="botao-primario mt-3" @click="adicionarSugestoes">Adicionar os marcados</button>
          </div>

          <p v-if="!ficha.acessos.length" class="text-sm text-tinta-suave">Nenhum acesso cadastrado.</p>
          <ul class="space-y-3">
            <li v-for="a in ficha.acessos" :key="a.id" class="cartao px-4 py-3">
              <div class="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span class="rotulo text-tinta">{{ rotuloDoTipo(a.tipo) }}</span>
                <span v-if="a.maquinaId && maquinaPorId[a.maquinaId]" class="text-xs text-tinta-suave">{{ maquinaPorId[a.maquinaId].nome }}</span>
                <span class="ml-auto flex flex-wrap gap-2 text-xs">
                  <a v-if="a.tipo === 'ANYDESK'" :href="`anydesk:${idSemEspacos(a)}`" class="underline underline-offset-4">Abrir no AnyDesk</a>
                  <button v-if="a.tipo === 'RDP' || a.tipo === 'RADMIN'" type="button" class="underline underline-offset-4" @click="baixarRdp(a)">Baixar .rdp</button>
                  <button v-if="modulos.editarClientes" type="button" class="underline underline-offset-4" @click="editarItem('acessos', a)">editar</button>
                  <button v-if="modulos.editarClientes && (modulos.senhasDosClientes || modulos.administrarClientes)" type="button" class="text-carimbo underline underline-offset-4" @click="apagarItem('acessos', a, 'este acesso (e a senha dele)')">apagar</button>
                </span>
              </div>
              <dl class="mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-[auto_1fr]">
                <dt class="rotulo self-center">ID / endereço</dt>
                <dd class="flex items-center gap-2">
                  <span class="carimbo text-base">{{ a.identificador }}<template v-if="a.porta">:{{ a.porta }}</template></span>
                  <button type="button" class="text-xs underline underline-offset-4" @click="copiarTexto(a.porta ? `${a.identificador}:${a.porta}` : a.identificador, 'ID')">copiar</button>
                </dd>
                <template v-if="a.usuario">
                  <dt class="rotulo self-center">Usuário</dt>
                  <dd class="flex items-center gap-2"><span class="carimbo">{{ a.usuario }}</span>
                    <button type="button" class="text-xs underline underline-offset-4" @click="copiarTexto(a.usuario, 'Usuário')">copiar</button></dd>
                </template>
                <template v-if="a.temSenha">
                  <dt class="rotulo self-center">Senha</dt>
                  <dd class="flex flex-wrap items-center gap-2">
                    <span class="carimbo">{{ visiveis[a.id] ?? '••••••••' }}</span>
                    <template v-if="modulos.senhasDosClientes">
                      <button type="button" class="text-xs underline underline-offset-4" @click="mostrarSenha(a)">{{ visiveis[a.id] ? 'esconder' : 'mostrar' }}</button>
                      <button type="button" class="text-xs underline underline-offset-4" @click="copiarSenha(a)">copiar</button>
                      <button type="button" class="text-xs text-carimbo underline underline-offset-4" @click="apagarSenha(a)">apagar senha</button>
                    </template>
                    <span class="text-xs text-tinta-apagada">alterada {{ dataHora(a.senhaAlteradaEm) }} por {{ a.senhaAlteradaPor }}</span>
                  </dd>
                </template>
                <template v-if="a.observacao">
                  <dt class="rotulo">Observação</dt>
                  <dd class="text-tinta-suave whitespace-pre-line">{{ a.observacao }}</dd>
                </template>
              </dl>
            </li>
          </ul>
        </section>

        <!-- MÁQUINAS -->
        <section v-if="aba === 'maquinas'" class="mt-4">
          <button v-if="modulos.editarClientes" type="button" class="botao-secundario mb-3" @click="novoItem('maquinas')">Nova máquina</button>
          <p v-if="!ficha.maquinas.length" class="text-sm text-tinta-suave">Nenhuma máquina cadastrada.</p>
          <ul class="cartao divide-y divide-linha" v-else>
            <li v-for="m in ficha.maquinas" :key="m.id" class="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-3 text-sm">
              <span class="font-semibold">{{ m.nome }}</span>
              <span class="rotulo">{{ rotuloDoPapel(m.papel) }}</span>
              <span v-if="m.pastaSistema" class="carimbo">{{ m.pastaSistema }}</span>
              <span v-if="m.observacao" class="w-full text-tinta-suave">{{ m.observacao }}</span>
              <span v-if="modulos.editarClientes" class="ml-auto flex gap-2 text-xs">
                <button type="button" class="underline underline-offset-4" @click="editarItem('maquinas', m)">editar</button>
                <button type="button" class="text-carimbo underline underline-offset-4" @click="apagarItem('maquinas', m, `a máquina ${m.nome}`)">apagar</button>
              </span>
            </li>
          </ul>
        </section>

        <!-- CONTATOS -->
        <section v-if="aba === 'contatos'" class="mt-4">
          <button v-if="modulos.editarClientes" type="button" class="botao-secundario mb-3" @click="novoItem('contatos')">Novo contato</button>
          <p v-if="!ficha.contatos.length" class="text-sm text-tinta-suave">Nenhum contato cadastrado.</p>
          <ul class="cartao divide-y divide-linha" v-else>
            <li v-for="ct in ficha.contatos" :key="ct.id" class="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-3 text-sm">
              <span class="font-semibold">{{ ct.nome }}</span>
              <span v-if="ct.funcao" class="text-tinta-suave">{{ ct.funcao }}</span>
              <span v-if="ct.autoriza" class="rounded-[2px] border border-credito/50 px-1.5 text-[0.7rem] font-bold text-credito">autoriza mudanças</span>
              <a v-if="ct.telefone" :href="`tel:${ct.telefone.replace(/[^\d+]/g, '')}`" class="carimbo underline underline-offset-4">{{ ct.telefone }}</a>
              <a v-if="ct.email" :href="`mailto:${ct.email}`" class="underline underline-offset-4">{{ ct.email }}</a>
              <span v-if="ct.observacao" class="w-full text-tinta-suave">{{ ct.observacao }}</span>
              <span v-if="modulos.editarClientes" class="ml-auto flex gap-2 text-xs">
                <button type="button" class="underline underline-offset-4" @click="editarItem('contatos', ct)">editar</button>
                <button type="button" class="text-carimbo underline underline-offset-4" @click="apagarItem('contatos', ct, `o contato ${ct.nome}`)">apagar</button>
              </span>
            </li>
          </ul>
        </section>

        <!-- ANOTAÇÕES -->
        <section v-if="aba === 'anotacoes'" class="mt-4">
          <form v-if="modulos.editarClientes" class="mb-4 flex flex-col gap-2" @submit.prevent="anotar">
            <textarea v-model="novaAnotacao" class="campo font-sans text-sm" rows="3" maxlength="4000" placeholder="O que foi feito, o que combinar, o que não mexer…" />
            <button type="submit" class="botao-secundario self-start" :disabled="!novaAnotacao.trim()">Guardar anotação</button>
          </form>
          <p v-if="!ficha.anotacoes.length" class="text-sm text-tinta-suave">Nenhuma anotação.</p>
          <ul class="space-y-2">
            <li v-for="n in ficha.anotacoes" :key="n.id" class="cartao px-4 py-3 text-sm">
              <p class="whitespace-pre-line">{{ n.texto }}</p>
              <p class="mt-1 flex gap-2 text-xs text-tinta-apagada">{{ n.autor }} · {{ dataHora(n.criadaEm) }}
                <button v-if="modulos.editarClientes" type="button" class="ml-auto underline underline-offset-4" @click="apagarItem('anotacoes', n, 'esta anotação')">apagar</button>
              </p>
            </li>
          </ul>
        </section>

        <!-- ALERTAS -->
        <section v-if="aba === 'alertas'" class="mt-4">
          <button v-if="modulos.editarClientes" type="button" class="botao-secundario mb-3" @click="novoItem('alertas')">Novo alerta</button>
          <p v-if="!ficha.alertas.length" class="text-sm text-tinta-suave">Nenhum alerta.</p>
          <ul class="cartao divide-y divide-linha" v-else>
            <li v-for="a in ficha.alertas" :key="a.id" class="flex flex-wrap items-baseline gap-x-4 gap-y-1 px-4 py-3 text-sm">
              <span class="min-w-0 flex-1" :class="a.ativo ? 'font-semibold text-carimbo' : 'text-tinta-apagada line-through'">{{ a.texto }}</span>
              <span class="text-xs text-tinta-apagada">{{ a.criadoPor }} · {{ dataHora(a.criadoEm) }}</span>
              <span v-if="modulos.editarClientes" class="flex gap-2 text-xs">
                <button type="button" class="underline underline-offset-4" @click="alternarAlerta(a)">{{ a.ativo ? 'desligar' : 'religar' }}</button>
                <button type="button" class="underline underline-offset-4" @click="editarItem('alertas', a)">editar</button>
                <button type="button" class="text-carimbo underline underline-offset-4" @click="apagarItem('alertas', a, 'este alerta')">apagar</button>
              </span>
            </li>
          </ul>
        </section>

        <!-- USO DAS SENHAS -->
        <section v-if="aba === 'usos'" class="mt-4">
          <p v-if="!usos" class="text-sm text-tinta-suave">Carregando…</p>
          <p v-else-if="!usos.length" class="text-sm text-tinta-suave">Ninguém usou as senhas deste cliente ainda.</p>
          <table v-else class="cartao w-full text-sm">
            <thead><tr class="text-left"><th class="rotulo px-3 py-2">Quando</th><th class="rotulo">Quem</th><th class="rotulo">O quê</th><th class="rotulo">Acesso</th><th class="rotulo">De onde</th></tr></thead>
            <tbody class="divide-y divide-linha">
              <tr v-for="u in usos" :key="u.id">
                <td class="px-3 py-2 whitespace-nowrap">{{ dataHora(u.em) }}</td>
                <td>{{ u.login }}</td>
                <td class="font-semibold">{{ u.acao.toLowerCase().replace(/_/g, ' ') }}</td>
                <td class="text-tinta-suave">{{ u.descricao }}</td>
                <td class="carimbo text-xs">{{ u.ip }}</td>
              </tr>
            </tbody>
          </table>
        </section>

        <!-- formulário de um item -->
        <div v-if="editando" class="fixed inset-0 z-40 grid place-items-center bg-tinta/30 px-4" role="dialog" aria-modal="true" @keydown.esc="editando = null">
          <form class="cartao grid w-full max-w-lg gap-3 px-5 py-4" @submit.prevent="salvarItem">
            <p class="rotulo">{{ editando.id ? 'Alterar' : 'Novo' }} · {{ abas.find((x) => x.id === editando.tipo)?.rotulo }}</p>

            <template v-if="editando.tipo === 'alertas'">
              <label><span class="rotulo">Alerta</span>
                <textarea v-model="editando.dados.texto" class="campo mt-1 font-sans" rows="2" maxlength="500" required placeholder="Nunca excluam nada: ligar para Jorge/Duda" /></label>
            </template>

            <template v-else-if="editando.tipo === 'contatos'">
              <label><span class="rotulo">Nome</span><input v-model="editando.dados.nome" class="campo mt-1 font-sans" maxlength="120" required /></label>
              <label><span class="rotulo">Função</span><input v-model="editando.dados.funcao" class="campo mt-1 font-sans" maxlength="80" /></label>
              <label><span class="rotulo">Telefone / WhatsApp</span><input v-model="editando.dados.telefone" class="campo mt-1" maxlength="40" /></label>
              <label><span class="rotulo">E-mail</span><input v-model="editando.dados.email" type="email" class="campo mt-1" maxlength="160" /></label>
              <label class="flex items-center gap-2 text-sm"><input v-model="editando.dados.autoriza" type="checkbox" /> Autoriza mudanças no sistema</label>
              <label><span class="rotulo">Observação</span><input v-model="editando.dados.observacao" class="campo mt-1 font-sans" maxlength="300" /></label>
            </template>

            <template v-else-if="editando.tipo === 'maquinas'">
              <label><span class="rotulo">Nome</span><input v-model="editando.dados.nome" class="campo mt-1 font-sans" maxlength="80" required placeholder="Servidor central" /></label>
              <label><span class="rotulo">Papel</span>
                <select v-model="editando.dados.papel" class="campo mt-1 font-sans"><option v-for="p in PAPEIS_DA_MAQUINA" :key="p.valor" :value="p.valor">{{ p.rotulo }}</option></select></label>
              <label><span class="rotulo">Pasta do sistema</span><input v-model="editando.dados.pastaSistema" class="campo mt-1" maxlength="260" placeholder="C:\sislojas" /></label>
              <label><span class="rotulo">Observação</span><textarea v-model="editando.dados.observacao" class="campo mt-1 font-sans" rows="2" maxlength="500" /></label>
            </template>

            <template v-else-if="editando.tipo === 'acessos'">
              <label><span class="rotulo">Tipo</span>
                <select v-model="editando.dados.tipo" class="campo mt-1 font-sans"><option v-for="t in TIPOS_DE_ACESSO" :key="t.valor" :value="t.valor">{{ t.rotulo }}</option></select></label>
              <div class="grid grid-cols-[1fr_7rem] gap-2">
                <label><span class="rotulo">ID ou endereço</span><input v-model="editando.dados.identificador" class="campo mt-1" maxlength="200" required /></label>
                <label><span class="rotulo">Porta</span><input v-model="editando.dados.porta" class="campo mt-1" inputmode="numeric" maxlength="5" /></label>
              </div>
              <label><span class="rotulo">Usuário</span><input v-model="editando.dados.usuario" class="campo mt-1" maxlength="120" autocomplete="off" /></label>
              <label v-if="modulos.senhasDosClientes"><span class="rotulo">{{ editando.id ? 'Senha nova (vazio: não muda)' : 'Senha' }}</span>
                <input v-model="editando.dados.senha" class="campo mt-1" type="password" maxlength="200" autocomplete="new-password" /></label>
              <label v-if="ficha.maquinas.length"><span class="rotulo">Máquina</span>
                <select v-model="editando.dados.maquinaId" class="campo mt-1 font-sans"><option :value="null">—</option><option v-for="m in ficha.maquinas" :key="m.id" :value="m.id">{{ m.nome }}</option></select></label>
              <label><span class="rotulo">Observação</span><textarea v-model="editando.dados.observacao" class="campo mt-1 font-sans" rows="2" maxlength="500" placeholder="Sempre colocar a porta ao conectar" /></label>
            </template>

            <p v-if="erroAcao" class="text-sm font-semibold text-carimbo" role="alert">{{ erroAcao }}</p>
            <div class="flex justify-end gap-2">
              <button type="button" class="botao-secundario" @click="editando = null">Cancelar</button>
              <button type="submit" class="botao-primario">Salvar</button>
            </div>
          </form>
        </div>
      </template>
    </EstadoDaTela>
  </div>
</template>
