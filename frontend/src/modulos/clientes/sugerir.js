/**
 * "Colar e sugerir": do texto das observações do sistema de chamados, as linhas que parecem acessos (AnyDesk,
 * UltraViewer, Radmin, TeamViewer, WTS) viram sugestões editáveis. Nada é gravado sem a pessoa conferir.
 *
 * @returns {Array<{ tipo, identificador, porta, usuario, senha, observacao }>}
 */
/** Parece ter uma senha escrita ("senha: x", "pass: x", "S: x")? As senhas vão só no campo Senha (o cofre). */
const SENHA_NO_TEXTO = /(\b(?:senha|pass(?:word)?)\s*[:=]?\s*|\bs\s*[:=]\s*)([^\s(),;]+)/gi

export function pareceTerSenha(texto) {
  if (!texto) return false
  SENHA_NO_TEXTO.lastIndex = 0
  return [...String(texto).matchAll(SENHA_NO_TEXTO)].some((m) => !/^(do|da|de|é|e)$/i.test(m[2]))
}

/** O texto com as senhas trocadas por •••• (para a observação nunca guardar a senha em claro). */
export function mascararSenhas(texto) {
  return String(texto ?? '').replace(SENHA_NO_TEXTO, (tudo, rotulo, valor) =>
    /^(do|da|de|é|e)$/i.test(valor) ? tudo : `${rotulo}••••`)
}

export function sugerirAcessos(texto) {
  if (!texto) return []
  const sugestoes = []
  for (const bruta of String(texto).split(/\r?\n/)) {
    const linha = bruta.trim()
    if (!linha) continue
    const minuscula = linha.toLowerCase()
    // a observação guarda a linha de origem, com as senhas mascaradas (elas vão só para o cofre)
    const sugestao = { tipo: null, identificador: '', porta: null, usuario: '', senha: '', observacao: mascararSenhas(linha).slice(0, 500) }

    const ip = linha.match(/\b(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{2,5}))?\b/)
    // AnyDesk "344 788 178", UltraViewer "56 907 558", ou tudo junto
    const numeros = linha.match(/\b\d{2,3}[ .-]\d{3}[ .-]\d{3,4}\b|\b\d{8,10}\b/)

    if (/any\s*desk|anydesk/.test(minuscula) && numeros) {
      sugestao.tipo = 'ANYDESK'
      sugestao.identificador = numeros[0].replace(/\D/g, '')
    } else if (/ultra/.test(minuscula) && numeros) {
      sugestao.tipo = 'ULTRAVIEWER'
      sugestao.identificador = numeros[0].replace(/\D/g, '')
    } else if (/team\s*viewer/.test(minuscula) && numeros) {
      sugestao.tipo = 'TEAMVIEWER'
      sugestao.identificador = numeros[0].replace(/\D/g, '')
    } else if (ip && (/radmin/.test(minuscula) || ip[1].startsWith('26.'))) {
      sugestao.tipo = 'RADMIN'
      sugestao.identificador = ip[1]
      sugestao.porta = ip[2] ? Number(ip[2]) : null
    } else if (ip) {
      sugestao.tipo = 'RDP'
      sugestao.identificador = ip[1]
      sugestao.porta = ip[2] ? Number(ip[2]) : null
    } else {
      continue
    }

    // senha: "senha: x", "senha x", "pass: x" ou "S: x" (o "s" sozinho só com dois-pontos); a primeira da linha
    const senha = linha.match(/(?:\bsenha|\bpass(?:word)?)\s*[:=]?\s*([^\s(),;]+)|\bs\s*[:=]\s*([^\s(),;]+)/i)
    const valorDaSenha = senha && (senha[1] ?? senha[2])
    if (valorDaSenha && !/^(do|da|de|é|e)$/i.test(valorDaSenha)) sugestao.senha = valorDaSenha
    // mais de uma senha na linha (ex.: a da rede Radmin e a do Windows): só a primeira vai para este acesso
    SENHA_NO_TEXTO.lastIndex = 0
    sugestao.outrasSenhas = Math.max(0, [...linha.matchAll(SENHA_NO_TEXTO)]
      .filter((m) => !/^(do|da|de|é|e)$/i.test(m[2])).length - 1)
    // usuário: "usuario w: x", "usu win - x", "user: x", "id: x"
    const usuario = linha.match(/(?:usu[aá]rio|usu|user|login|\bid)\s*(?:w(?:in)?\s*)?[:=-]?\s*([^\s(),;:]+)/i)
    if (usuario && usuario[1] !== sugestao.senha) sugestao.usuario = usuario[1]
    if (sugestao.porta && (sugestao.porta < 1 || sugestao.porta > 65535)) sugestao.porta = null
    sugestoes.push(sugestao)
  }
  return sugestoes
}
