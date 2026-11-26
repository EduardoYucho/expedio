import { http } from '@/api/http'

/** Rotas dos Clientes (todas devolvem o conteúdo de "dados"). Nenhuma, fora as do cofre, traz senha. */
export const clientesApi = {
  /** { ver, editar, senhas, administrar } */
  meuAcesso: () => http.get('/clientes/meu-acesso'),
  /** [{ id, nome, grupo, cidade, ativo, codigoChamados, alertas, tiposDeAcesso }] */
  buscar: (busca, situacao = 'ativos') => http.get('/clientes', { params: { busca, situacao } }),
  grupos: () => http.get('/clientes/grupos'),
  criar: (cliente) => http.post('/clientes', cliente),
  /** { cliente, alertas, contatos, maquinas, acessos, credenciais, anotacoes } */
  ficha: (id) => http.get(`/clientes/${id}`),
  alterar: (id, cliente) => http.put(`/clientes/${id}`, cliente),
  apagar: (id) => http.delete(`/clientes/${id}`),

  /** tipo: alertas | contatos | maquinas | acessos | credenciais | anotacoes */
  criarItem: (id, tipo, item) => http.post(`/clientes/${id}/${tipo}`, item),
  alterarItem: (id, tipo, itemId, item) => http.put(`/clientes/${id}/${tipo}/${itemId}`, item),
  apagarItem: (id, tipo, itemId) => http.delete(`/clientes/${id}/${tipo}/${itemId}`),
}

const rotaDaSenha = (alvo) =>
  alvo.credencial ? `/clientes/cofre/credenciais/${alvo.credencial}/senha` : `/clientes/cofre/acessos/${alvo.acesso}/senha`

const comCofre = (token) => ({ headers: { 'X-Cofre': token ?? '' } })

/** O cofre: o token (X-Cofre) vem do abrir e fica só na memória da aba. */
export const cofreApi = {
  estado: (token) => http.get('/clientes/cofre/estado', comCofre(token)),
  novoAutenticador: () => http.post('/clientes/cofre/autenticador'),
  ativar: (codigo) => http.post('/clientes/cofre/autenticador/ativar', { codigo }),
  abrir: (codigo) => http.post('/clientes/cofre/abrir', { codigo }),
  fechar: (token) => http.post('/clientes/cofre/fechar', null, comCofre(token)),
  /**
   * alvo: { acesso, campo } (campo "rede": a senha da rede do Radmin/VPN) ou { credencial } (usuário do cliente).
   * acao: MOSTRAR | COPIAR → { senha }
   */
  senha: (token, alvo, acao) => http.post(rotaDaSenha(alvo), { acao, campo: alvo.campo }, comCofre(token)),
  /** senha vazia apaga */
  gravarSenha: (token, alvo, senha) => http.put(rotaDaSenha(alvo), { senha, campo: alvo.campo }, comCofre(token)),
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

export const ONDE_DA_CREDENCIAL = [
  { valor: 'SISTEMA', rotulo: 'Sistema da loja' },
  { valor: 'WINDOWS', rotulo: 'Windows' },
  { valor: 'BANCO', rotulo: 'Banco de dados' },
  { valor: 'OUTRO', rotulo: 'Outros serviços' },
]

export const rotuloDoOnde = (valor) => ONDE_DA_CREDENCIAL.find((o) => o.valor === valor)?.rotulo ?? valor

/** Tipos de acesso que entram numa rede (com nome e senha da rede, além do login da máquina). */
export const TEM_REDE = ['RADMIN', 'VPN']

/** Os rótulos dos campos do acesso conforme o tipo. */
export function camposDoAcesso(tipo) {
  switch (tipo) {
    case 'RADMIN':
      return { identificador: 'IP na rede', rede: 'Rede', senhaDaRede: 'Senha da rede', usuario: 'Usuário do Windows', senha: 'Senha do Windows', porta: true }
    case 'VPN':
      return { identificador: 'Servidor da VPN', rede: 'Usuário da VPN', senhaDaRede: 'Senha da VPN', usuario: 'Usuário da máquina', senha: 'Senha da máquina', porta: true }
    case 'RDP':
      return { identificador: 'Endereço', usuario: 'Usuário do Windows', senha: 'Senha do Windows', porta: true }
    case 'ANYDESK':
    case 'ULTRAVIEWER':
    case 'TEAMVIEWER':
      return { identificador: 'ID', usuario: 'Usuário', senha: 'Senha de acesso', porta: false }
    default:
      return { identificador: 'ID ou endereço', usuario: 'Usuário', senha: 'Senha', porta: true }
  }
}
