import { http } from '@/api/http'

/** Rotas dos Clientes (todas devolvem o conteúdo de "dados"). Nenhuma, fora as do cofre, traz senha. */
export const clientesApi = {
  /** { ver, editar, senhas, administrar } */
  meuAcesso: () => http.get('/clientes/meu-acesso'),
  /** [{ id, nome, grupo, cidade, ativo, codigoChamados, alertas, tiposDeAcesso }] */
  buscar: (busca, situacao = 'ativos') => http.get('/clientes', { params: { busca, situacao } }),
  grupos: () => http.get('/clientes/grupos'),
  criar: (cliente) => http.post('/clientes', cliente),
  /** { cliente, alertas, contatos, maquinas, acessos, anotacoes } */
  ficha: (id) => http.get(`/clientes/${id}`),
  alterar: (id, cliente) => http.put(`/clientes/${id}`, cliente),
  apagar: (id) => http.delete(`/clientes/${id}`),

  /** tipo: alertas | contatos | maquinas | acessos | anotacoes */
  criarItem: (id, tipo, item) => http.post(`/clientes/${id}/${tipo}`, item),
  alterarItem: (id, tipo, itemId, item) => http.put(`/clientes/${id}/${tipo}/${itemId}`, item),
  apagarItem: (id, tipo, itemId) => http.delete(`/clientes/${id}/${tipo}/${itemId}`),
}

const comCofre = (token) => ({ headers: { 'X-Cofre': token ?? '' } })

/** O cofre: o token (X-Cofre) vem do abrir e fica só na memória da aba. */
export const cofreApi = {
  estado: (token) => http.get('/clientes/cofre/estado', comCofre(token)),
  novoAutenticador: () => http.post('/clientes/cofre/autenticador'),
  ativar: (codigo) => http.post('/clientes/cofre/autenticador/ativar', { codigo }),
  abrir: (codigo) => http.post('/clientes/cofre/abrir', { codigo }),
  fechar: (token) => http.post('/clientes/cofre/fechar', null, comCofre(token)),
  /** acao: MOSTRAR | COPIAR → { senha } */
  senha: (token, acessoId, acao) => http.post(`/clientes/cofre/acessos/${acessoId}/senha`, { acao }, comCofre(token)),
  /** senha vazia apaga */
  gravarSenha: (token, acessoId, senha) => http.put(`/clientes/cofre/acessos/${acessoId}/senha`, { senha }, comCofre(token)),
  usos: (cliente, limite = 200) => http.get('/clientes/cofre/usos', { params: { cliente, limite } }),
  autenticadores: () => http.get('/clientes/cofre/autenticadores'),
  zerarAutenticador: (usuarioId) => http.delete(`/clientes/cofre/autenticadores/${usuarioId}`),
  definirFrase: (token, frase) => http.post('/clientes/cofre/recuperacao', { frase }, comCofre(token)),
  restaurar: (frase) => http.post('/clientes/cofre/recuperacao/restaurar', { frase }),
}

export const TIPOS_DE_ACESSO = [
  { valor: 'ANYDESK', rotulo: 'AnyDesk' },
  { valor: 'ULTRAVIEWER', rotulo: 'UltraViewer' },
  { valor: 'RADMIN', rotulo: 'Radmin VPN' },
  { valor: 'RDP', rotulo: 'WTS / Área de trabalho remota' },
  { valor: 'TEAMVIEWER', rotulo: 'TeamViewer' },
  { valor: 'VPN', rotulo: 'VPN' },
  { valor: 'OUTRO', rotulo: 'Outro' },
]

export const PAPEIS_DA_MAQUINA = [
  { valor: 'SERVIDOR_CENTRAL', rotulo: 'Servidor central' },
  { valor: 'SERVIDOR', rotulo: 'Servidor' },
  { valor: 'CAIXA', rotulo: 'Caixa' },
  { valor: 'ESTACAO', rotulo: 'Estação' },
  { valor: 'OUTRA', rotulo: 'Outra' },
]

export const rotuloDoTipo = (valor) => TIPOS_DE_ACESSO.find((t) => t.valor === valor)?.rotulo ?? valor
export const rotuloDoPapel = (valor) => PAPEIS_DA_MAQUINA.find((p) => p.valor === valor)?.rotulo ?? valor
