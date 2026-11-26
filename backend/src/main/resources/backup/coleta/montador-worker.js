// No navegador de quem recebe (a tela Backups de clientes do sistema), fora da tela para não travá-la:
//  - montar: junta os pedaços gravados em <pasta>/<nome>.partes num <nome>.tar.zst, confere cada arquivo pelo SHA-256
//    do manifesto, grava o relatório <nome>.json e apaga os pedaços;
//  - extrair: descompacta um backup montado numa pasta escolhida.
// As pastas são do computador de quem recebe (escolhidas no seletor do navegador).
import { Module } from './vendor/zstd.js';
import { criarDescompactador } from './nucleo/zstd-descompactar.js';
import { montar, extrair, nomeDaParte, Conferencia } from './nucleo/montador.js';

const pronto = new Promise((resolver) => {
  Module.onRuntimeInitialized = resolver;
});
Module.init(new URL('./vendor/zstd.wasm', import.meta.url).href);

async function lerArquivo(pasta, nome) {
  const arquivo = await (await pasta.getFileHandle(nome)).getFile();
  return new Uint8Array(await arquivo.arrayBuffer());
}

async function gravarTexto(pasta, nome, texto) {
  const escrita = await (await pasta.getFileHandle(nome, { create: true })).createWritable();
  await escrita.write(texto);
  await escrita.close();
}

async function acaoMontar({ pasta, nome, quantidade, relatorio }) {
  const partes = await pasta.getDirectoryHandle(`${nome}.partes`);
  const escrita = await (await pasta.getFileHandle(`${nome}.tar.zst`, { create: true })).createWritable();
  let resultado;
  try {
    resultado = await montar({
      quantidade,
      lerParte: (i) => lerArquivo(partes, nomeDaParte(i)),
      lerFinal: () => lerArquivo(partes, 'final.zst'),
      escrever: (bytes) => escrita.write(bytes),
      descompactar: criarDescompactador(Module),
      aoAvancar: (p) => self.postMessage({ progresso: p }),
    });
    await escrita.close();
  } catch (erro) {
    try {
      await escrita.abort();
    } catch (e) {
      /* nada */
    }
    throw erro;
  }
  await gravarTexto(pasta, `${nome}.json`, JSON.stringify({ ...relatorio, arquivo: `${nome}.tar.zst`, conferencia: { ...resultado, quadros: undefined }, quadros: resultado.quadros }, null, 2));
  try {
    await pasta.removeEntry(`${nome}.partes`, { recursive: true });
  } catch (e) {
    /* fica para apagar à mão */
  }
  return resultado;
}

async function acaoExtrair({ pasta, nome, destino }) {
  const relatorio = JSON.parse(new TextDecoder().decode(await lerArquivo(pasta, `${nome}.json`)));
  const quadros = relatorio.quadros || [];
  if (!quadros.length) throw new Error(`O relatório ${nome}.json não traz o índice do arquivo.`);
  const arquivo = await (await pasta.getFileHandle(`${nome}.tar.zst`)).getFile();
  const inicios = [];
  let o = 0;
  for (const q of quadros) {
    inicios.push(o);
    o += q;
  }
  const pastaDe = async (partes) => {
    let atual = destino;
    for (const p of partes) atual = await atual.getDirectoryHandle(p, { create: true });
    return atual;
  };
  return extrair({
    quantidade: quadros.length,
    lerQuadro: async (i) => new Uint8Array(await arquivo.slice(inicios[i], inicios[i] + quadros[i]).arrayBuffer()),
    descompactar: criarDescompactador(Module),
    criarPasta: (partes) => pastaDe(partes),
    criarArquivo: async (partes) => {
      const dir = await pastaDe(partes.slice(0, -1));
      const escrita = await (await dir.getFileHandle(partes[partes.length - 1], { create: true })).createWritable();
      return { escrever: (b) => escrita.write(b), fechar: () => escrita.close() };
    },
    aoAvancar: (p) => self.postMessage({ progresso: p }),
  });
}

// ---------------------------------------------------------------- acompanhar: montar e conferir durante o recebimento
//  {acao:'acompanhar', pasta, nome, ja}: abre <nome>.tar.zst e passa por ele (e pela conferência) os pedaços 0..ja-1
//    que já estão gravados em <nome>.partes; responde {pronto, proximo}.
//  {acao:'parte', seq, bytes}: o pedaço seq (já gravado em .partes e confirmado): vai para o .tar.zst e é conferido;
//    um que faltou no meio é lido do .partes. Responde {ack: seq, bytesConferidos}.
//  {acao:'fechar', final, relatorio}: o último pedaço; fecha o .tar.zst, compara com o manifesto, grava o relatório e
//    apaga o .partes. Responde {fim, resultado}. Qualquer erro: {fim, erro} (quem chama monta do jeito antigo).
let sessao = null;

async function acompanhar({ pasta, nome, ja }) {
  const partes = await pasta.getDirectoryHandle(`${nome}.partes`, { create: true });
  const escrita = await (await pasta.getFileHandle(`${nome}.tar.zst`, { create: true })).createWritable();
  sessao = { pasta, nome, partes, escrita, conferencia: new Conferencia(criarDescompactador(Module)), proximo: 0 };
  // na fila: pedaços novos só entram depois dos que já estavam gravados
  sessao.fila = ate(ja).then(() => self.postMessage({ pronto: true, proximo: sessao.proximo }));
  await sessao.fila;
}

async function passar(quadro) {
  await sessao.escrita.write(quadro);
  const andamento = sessao.conferencia.alimentar(quadro);
  sessao.proximo++;
  return andamento;
}

/** Passa os pedaços gravados de proximo até seq-1 (lidos do .partes). */
async function ate(seq) {
  while (sessao.proximo < seq) await passar(await lerArquivo(sessao.partes, nomeDaParte(sessao.proximo)));
}

async function parte({ seq, bytes }) {
  if (seq >= sessao.proximo) {
    await ate(seq);
    await passar(bytes);
  }
  self.postMessage({ ack: seq, bytesConferidos: sessao.conferencia.bytesConferidos });
}

async function fecharSessao({ final, relatorio, quantidade }) {
  await ate(quantidade);
  await passar(final);
  await sessao.escrita.close();
  const resultado = sessao.conferencia.concluir();
  const { pasta, nome } = sessao;
  sessao = null;
  await gravarTexto(pasta, `${nome}.json`, JSON.stringify({ ...relatorio, arquivo: `${nome}.tar.zst`, conferencia: { ...resultado, quadros: undefined }, quadros: resultado.quadros }, null, 2));
  try {
    await pasta.removeEntry(`${nome}.partes`, { recursive: true });
  } catch (e) {
    /* fica para apagar à mão */
  }
  return resultado;
}

async function falhouSessao(erro) {
  const s = sessao;
  sessao = null;
  if (s) {
    try {
      await s.escrita.abort();
    } catch (e) {
      /* nada */
    }
  }
  self.postMessage({ fim: true, erro: `${erro.name || 'Erro'}: ${erro.message || erro}` });
}

function naSessao(pedido) {
  if (!sessao) return;
  sessao.fila = sessao.fila.then(async () => {
    if (!sessao) return;
    try {
      if (pedido.acao === 'parte') await parte(pedido);
      else self.postMessage({ fim: true, resultado: await fecharSessao(pedido) });
    } catch (erro) {
      await falhouSessao(erro);
    }
  });
}

self.onmessage = async (evento) => {
  const pedido = evento.data;
  if (pedido.acao === 'parte' || pedido.acao === 'fechar') {
    naSessao(pedido);
    return;
  }
  if (pedido.acao === 'acompanhar') {
    try {
      await pronto;
      await acompanhar(pedido);
    } catch (erro) {
      await falhouSessao(erro);
    }
    return;
  }
  try {
    await pronto;
    const resultado = pedido.acao === 'extrair' ? await acaoExtrair(pedido) : await acaoMontar(pedido);
    self.postMessage({ fim: true, resultado });
  } catch (erro) {
    self.postMessage({ fim: true, erro: `${erro.name || 'Erro'}: ${erro.message || erro}` });
  }
};
