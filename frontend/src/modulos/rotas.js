/**
 * Telas dos módulos (atendimentos, base de conhecimento, monitor fiscal, backups de clientes, clientes), carregadas sob demanda como as do ponto. O router
 * só importa esta lista: as telas, as chamadas à API e o estado ficam todos em src/modulos.
 */
export const rotasDosModulos = [
  {
    path: '/atendimentos',
    name: 'atendimentos',
    component: () => import('./atendimento/views/AtendimentosView.vue'),
  },
  {
    path: '/atendimentos/chave-gemini',
    name: 'chave-gemini',
    component: () => import('./atendimento/views/MinhaChaveGeminiView.vue'),
  },
  {
    path: '/atendimentos/novo',
    name: 'atendimento-novo',
    component: () => import('./atendimento/views/NovoAtendimentoView.vue'),
  },
  {
    path: '/atendimentos/:id',
    name: 'atendimento',
    component: () => import('./atendimento/views/AtendimentoView.vue'),
    props: true,
  },
  {
    path: '/base-conhecimento',
    name: 'base-conhecimento',
    component: () => import('./conhecimento/views/BaseConhecimentoView.vue'),
  },
  {
    path: '/base-conhecimento/:id',
    name: 'registro-da-base',
    component: () => import('./conhecimento/views/RegistroView.vue'),
    props: true,
  },
  {
    path: '/monitor-fiscal',
    name: 'monitor-fiscal',
    component: () => import('./fiscal/views/MonitorFiscalView.vue'),
    meta: { permissao: 'fiscal.ver' },
  },
  {
    path: '/backups-clientes',
    name: 'backups-clientes',
    component: () => import('./backup/views/BackupsDeClientesView.vue'),
    meta: { permissao: 'backup.coletar' },
  },
  {
    path: '/clientes',
    name: 'clientes',
    component: () => import('./clientes/views/ClientesView.vue'),
    meta: { permissao: 'clientes.ver' },
  },
  {
    path: '/clientes/cofre',
    name: 'cofre-dos-clientes',
    component: () => import('./clientes/views/CofreAdminView.vue'),
    meta: { permissao: 'clientes.administrar' },
  },
  {
    path: '/clientes/:id',
    name: 'cliente',
    component: () => import('./clientes/views/FichaDoClienteView.vue'),
    props: true,
    meta: { permissao: 'clientes.ver' },
  },
  // a antiga tela de acessos dos módulos: os acessos agora ficam nos perfis de cada pessoa, em Usuários
  { path: '/acessos', redirect: '/usuarios' },
]
