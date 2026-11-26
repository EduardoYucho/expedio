// Teste de ponta a ponta do coletor nativo (nativo/coletor.ps1), no Windows, contra o sistema rodando de verdade:
//  - o cliente é o COMANDO que a tela copia (baixa o coletor do próprio sistema e roda no PowerShell 5.1);
//  - quem recebe é este script, como recepcao.js + o worker: grava cada pedaço em .partes e, ao mesmo tempo, monta o
//    .tar.zst e confere (a Conferencia de montador.js); no fim só fecha.
// Cenários: coleta inteira com a cópia de sombra (um .cfg travado por outro processo vem inteiro), retomada depois de
// matar o coletor no meio, e sem a cópia de sombra (o travado vai para os não lidos, com o motivo).
//
//   node src/test/js/coletor-nativo-ponta-a-ponta.mjs <porta> <porta-publica> <pasta-de-trabalho> <login> <senha>
//
// Precisa de PowerShell como administrador para a cópia de sombra (sem isso, o primeiro cenário avisa e pula).

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { fileURLToPath, pathToFileURL } from 'node:url';

const aqui = path.dirname(fileURLToPath(import.meta.url));
const estaticos = path.resolve(aqui, '../../main/resources/backup/coleta');
const modulo = (p) => import(pathToFileURL(path.join(estaticos, p)).href);
const { criarDescompactador } = await modulo('nucleo/zstd-descompactar.js');
const { Conferencia, extrair, nomeDaParte } = await modulo('nucleo/montador.js');
const { Module } = await modulo('vendor/zstd.js');
const { novaChave, importarChave, decifrar } = await modulo('nucleo/cifra.js');

const [porta, portaPublica, trabalho, login, senha] = process.argv.slice(2);
const SISTEMA = `http://127.0.0.1:${porta}`;
const PUBLICO = `http://127.0.0.1:${portaPublica}`;

let falhas = 0;
function confere(condicao, mensagem) {
  if (condicao) console.log(`  ok  ${mensagem}`);
  else {
    falhas++;
    console.log(`  FALHOU  ${mensagem}`);
  }
}

const pronto = new Promise((r) => { Module.onRuntimeInitialized = r; });
Module.init(fs.readFileSync(path.join(estaticos, 'vendor/zstd.wasm')));
await pronto;
const descompactar = criarDescompactador(Module);
const admin = (() => {
  try {
    execFileSync('net', ['session'], { stdio: 'ignore' });
    return true;
  } catch {
    return false;
  }
})();

// ------------------------------------------------------------------ pasta de exemplo
fs.rmSync(trabalho, { recursive: true, force: true });
const origem = path.join(trabalho, 'origem', 'sislojas');
const p = (...x) => path.join(origem, ...x);
fs.mkdirSync(p('Cfg'), { recursive: true });
fs.mkdirSync(p('Data'), { recursive: true });
fs.mkdirSync(p('Vazia'), { recursive: true });
const fundo = p('Logs', 'a'.repeat(60), 'b'.repeat(60), 'c'.repeat(60), 'd'.repeat(60));
fs.mkdirSync(fundo, { recursive: true });
fs.writeFileSync(path.join(fundo, 'erro com acentuação.log'), 'erro\n'.repeat(5000));
fs.writeFileSync(p('Cfg', 'sistema.cfg'), '[base]\ncaminho=C:\\sislojas\\Data\nempresa=1\n');
fs.writeFileSync(p('Cfg', 'Configuração da Loja.cfg'), 'loja=1\nimpressora=EPSON\n'.repeat(30));
fs.writeFileSync(p('Data', 'VAZIO.DAT'), Buffer.alloc(0));
for (let i = 0; i < 400; i++) fs.writeFileSync(p('Data', `T${String(i).padStart(4, '0')}.DAT`), `registro ${i};`.repeat(50 + (i % 300)));
{
  // grande (vários blocos de 8 MiB), meio compressível
  const grande = Buffer.alloc(30 * 1024 * 1024 + 123);
  const aleatorio = crypto.randomBytes(grande.length / 8);
  for (let i = 0; i < grande.length; i++) grande[i] = i % 3 === 0 ? aleatorio[i % aleatorio.length] : 65 + (i % 26);
  fs.writeFileSync(p('Data', 'MOVIMENTO.DAT'), grande);
}
fs.writeFileSync(p('leia-me.txt'), 'arquivo solto na raiz\n');
const travado = p('Cfg', 'em-uso.cfg');
fs.writeFileSync(travado, 'senha_do_banco=nao_e_real\nporta=5432\n'.repeat(100));
const shaTravado = crypto.createHash('sha256').update(fs.readFileSync(travado)).digest('hex');
// uma junção dentro da pasta (não deve ser seguida)
fs.mkdirSync(path.join(trabalho, 'fora'), { recursive: true });
fs.writeFileSync(path.join(trabalho, 'fora', 'nao-deve-vir.txt'), 'x');
execFileSync('cmd', ['/c', 'mklink', '/J', p('Atalho'), path.join(trabalho, 'fora')], { stdio: 'ignore' });

/** Um processo que segura o .cfg aberto sem compartilhar (como um sistema que trava o arquivo). */
async function travar(arquivo) {
  const ps = spawn('powershell.exe', ['-NoProfile', '-Command',
    `$f=[IO.File]::Open('${arquivo}','Open','ReadWrite','None'); 'travado'; [Console]::In.ReadLine() | Out-Null; $f.Close()`],
  { stdio: ['pipe', 'pipe', 'inherit'] });
  await new Promise((r) => ps.stdout.once('data', r));
  return ps;
}

// ------------------------------------------------------------------ HTTP
let token;
{
  const r = await fetch(`${SISTEMA}/api/v1/auth/login`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ login, senha }) });
  token = (await r.json()).dados.token;
}
async function api(metodo, caminho, corpo) {
  const r = await fetch(`${SISTEMA}/api/v1/backup${caminho}`, {
    method: metodo, headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    body: corpo === undefined ? undefined : JSON.stringify(corpo),
  });
  const texto = await r.text();
  if (!r.ok) throw new Error(`${metodo} ${caminho}: ${r.status} ${texto}`);
  return texto ? JSON.parse(texto).dados : null;
}

// ------------------------------------------------------------------ quem recebe (como recepcao.js + worker 'acompanhar')
async function receptor(id, destino, nome, chave, aoPedaco = () => {}) {
  const partes = path.join(destino, `${nome}.partes`);
  const arquivo = path.join(destino, `${nome}.tar.zst`);
  fs.mkdirSync(partes, { recursive: true });
  let conf = null;
  let proximo = 0;
  const recomecar = (ate) => {
    // monta de novo a partir do que já está em .partes (como o worker quando a aba abre de novo)
    fs.writeFileSync(arquivo, Buffer.alloc(0));
    conf = new Conferencia(descompactar);
    for (let i = 0; i < ate; i++) {
      const q = new Uint8Array(fs.readFileSync(path.join(partes, nomeDaParte(i))));
      fs.appendFileSync(arquivo, q);
      conf.alimentar(q);
    }
    proximo = ate;
  };
  let recomecos = 0;
  for (;;) {
    const atual = await api('GET', `/coletas/${id}`);
    if (atual.situacao === 'FECHANDO') break;
    if (!['AGUARDANDO', 'RECEBENDO'].includes(atual.situacao)) return { coleta: atual };
    if (!conf) recomecar(atual.proximoSeq);
    for (;;) {
      const r = await fetch(`${SISTEMA}/api/v1/backup/coletas/${id}/receber`, { headers: { Authorization: `Bearer ${token}` } });
      if (r.status !== 200) break;
      const bytes = new Uint8Array(await r.arrayBuffer());
      const seq = Number(r.headers.get('x-seq'));
      const sha = crypto.createHash('sha256').update(bytes).digest('hex');
      if (sha !== r.headers.get('x-sha256')) continue;
      const conteudo = await decifrar(chave, bytes, seq);
      if (seq < proximo) {
        recomecos++;
        recomecar(seq);
      }
      fs.writeFileSync(path.join(partes, nomeDaParte(seq)), conteudo);
      await api('POST', `/coletas/${id}/recebido`, { seq, sha256: sha });
      while (proximo < seq) {
        const q = new Uint8Array(fs.readFileSync(path.join(partes, nomeDaParte(proximo))));
        fs.appendFileSync(arquivo, q);
        conf.alimentar(q);
        proximo++;
      }
      fs.appendFileSync(arquivo, conteudo);
      conf.alimentar(conteudo);
      proximo = seq + 1;
      await aoPedaco(seq);
    }
  }
  const coleta = await api('GET', `/coletas/${id}`);
  if (!conf || proximo !== coleta.proximoSeq) recomecar(coleta.proximoSeq);
  const final = new Uint8Array(await (await fetch(`${SISTEMA}/api/v1/backup/coletas/${id}/final`, { headers: { Authorization: `Bearer ${token}` } })).arrayBuffer());
  fs.appendFileSync(arquivo, final);
  conf.alimentar(final);
  const resultado = conf.concluir();
  fs.writeFileSync(path.join(destino, `${nome}.json`), JSON.stringify({ quadros: resultado.quadros }));
  fs.rmSync(partes, { recursive: true, force: true });
  const { quadros, ...conferencia } = resultado;
  return { coleta: await api('POST', `/coletas/${id}/resultado`, conferencia), recomecos };
}

async function extrairNode(destino, nome, alvo) {
  const { quadros } = JSON.parse(fs.readFileSync(path.join(destino, `${nome}.json`), 'utf8'));
  const dados = fs.readFileSync(path.join(destino, `${nome}.tar.zst`));
  const inicios = [];
  let o = 0;
  for (const q of quadros) { inicios.push(o); o += q; }
  return extrair({
    quantidade: quadros.length,
    lerQuadro: async (i) => new Uint8Array(dados.subarray(inicios[i], inicios[i] + quadros[i])),
    descompactar,
    criarPasta: async (partes) => fs.mkdirSync(path.join(alvo, ...partes), { recursive: true }),
    criarArquivo: async (partes) => {
      fs.mkdirSync(path.join(alvo, ...partes.slice(0, -1)), { recursive: true });
      const caminho = path.join(alvo, ...partes);
      fs.writeFileSync(caminho, Buffer.alloc(0));
      return { escrever: async (b) => fs.appendFileSync(caminho, b), fechar: async () => {} };
    },
  });
}

/** Lista "caminho sha" de uma pasta (junções e o que estiver em {pular} ficam de fora). */
function listaDe(raiz, pular = []) {
  const saida = [];
  const andar = (pasta, rel) => {
    for (const item of fs.readdirSync(pasta, { withFileTypes: true })) {
      const r = rel ? `${rel}/${item.name}` : item.name;
      if (item.isSymbolicLink() || pular.includes(r)) continue;
      const c = path.join(pasta, item.name);
      if (item.isDirectory()) { saida.push(`${r}/`); andar(c, r); } else saida.push(`${r} ${crypto.createHash('sha256').update(fs.readFileSync(c)).digest('hex')}`);
    }
  };
  andar(raiz, '');
  return saida.sort();
}
function iguais(a, b) {
  const ok = a.length === b.length && a.every((x, i) => x === b[i]);
  if (!ok) {
    console.log('    só na origem :', a.filter((x) => !b.includes(x)).slice(0, 5));
    console.log('    só no extraído:', b.filter((x) => !a.includes(x)).slice(0, 5));
  }
  return ok;
}

/** O comando que a tela copia (o mesmo texto de comandoDe), com as opções para não perguntar nada. */
function comando(link, extra) {
  const base = new URL(link).origin;
  return "[Net.ServicePointManager]::SecurityProtocol=3072; $w=New-Object Net.WebClient; $w.Encoding=[Text.Encoding]::UTF8; "
    + `& ([scriptblock]::Create($w.DownloadString('${base}/c/_/nativo/coletor.ps1'))) -Link '${link}' ${extra}; exit $LASTEXITCODE`;
}
function rodarColetor(link, extra, log) {
  const filho = spawn('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', comando(link, extra)], { stdio: ['ignore', 'pipe', 'pipe'] });
  const saida = fs.createWriteStream(log);
  filho.stdout.pipe(saida);
  filho.stderr.pipe(saida);
  const fim = new Promise((r) => filho.on('exit', (codigo) => r(codigo)));
  return { filho, fim };
}
const sombrasDoSistema = () => {
  try {
    return Number(execFileSync('powershell.exe', ['-NoProfile', '-Command', '@(Get-CimInstance Win32_ShadowCopy).Count'], { encoding: 'utf8' }).trim());
  } catch {
    return -1;
  }
};

const meuComputador = path.join(trabalho, 'meu-computador');
fs.mkdirSync(meuComputador, { recursive: true });
await api('POST', '/clientes', { nome: 'Loja Nativa', pastaSistema: origem }).catch(() => {});
const sombrasAntes = admin ? sombrasDoSistema() : -1;

async function novaColeta() {
  const c = await api('POST', '/coletas', { cliente: 'Loja Nativa' });
  const textoDaChave = novaChave();
  return { c, chave: await importarChave(textoDaChave), link: `${PUBLICO}/c/${c.token}#k=${textoDaChave}` };
}

// ------------------------------------------------------------------ 1. com a cópia de sombra
if (!admin) {
  console.log('Coleta com a cópia de sombra: PULADA (rode como administrador)');
} else {
  console.log('Coleta inteira com a cópia de sombra (um .cfg travado por outro processo)');
  const trava = await travar(travado);
  try {
    let bloqueado = false;
    try { fs.readFileSync(travado); } catch { bloqueado = true; }
    confere(bloqueado, 'o .cfg está mesmo travado (ninguém mais consegue ler)');
    const { c, chave, link } = await novaColeta();
    const inicio = Date.now();
    const recebendo = receptor(c.id, meuComputador, c.nome, chave);
    const { fim } = rodarColetor(link, `-Pasta '${origem}' -Tudo`, path.join(trabalho, 'coletor-1.log'));
    const codigo = await fim;
    const { coleta } = await recebendo;
    const log = fs.readFileSync(path.join(trabalho, 'coletor-1.log'), 'utf8');
    confere(codigo === 0, `o coletor terminou bem (código ${codigo})`);
    if (codigo !== 0) console.log(log.split('\n').slice(-25).join('\n'));
    confere(/sombra/.test(log) && /Pronta/.test(log), 'usou a cópia de sombra');
    confere(coleta.situacao === 'INTEGRA', `conferido: ${coleta.conferencia?.mensagem} (${((Date.now() - inicio) / 1000).toFixed(1)} s)`);
    confere(/Backup recebido inteiro e conferido/.test(log), 'o coletor mostra que chegou inteiro e conferido');
    confere(coleta.resumo?.naoLidos === 1 && coleta.naoLidos?.[0]?.includes('Atalho') && coleta.naoLidos[0].includes('junção'),
      `só a junção ficou de fora, e não foi seguida (${JSON.stringify(coleta.naoLidos)})`);
    const alvo = path.join(trabalho, 'extraido-1');
    await extrairNode(meuComputador, c.nome, alvo);
    trava.stdin.end();
    await new Promise((r) => trava.on('exit', r));
    confere(crypto.createHash('sha256').update(fs.readFileSync(path.join(alvo, 'sislojas', 'Cfg', 'em-uso.cfg'))).digest('hex') === shaTravado,
      'o .cfg travado veio inteiro');
    confere(iguais(listaDe(origem), listaDe(path.join(alvo, 'sislojas'))), 'extraído idêntico à origem (acentos, caminho de 260+, vazios, grande)');
    try {
      const d = path.join(trabalho, 'tar-do-windows-1');
      fs.mkdirSync(d);
      execFileSync('C:\\Windows\\System32\\tar.exe', ['-xf', path.join(meuComputador, `${c.nome}.tar.zst`), '-C', d], { stdio: 'pipe' });
      confere(fs.existsSync(path.join(d, 'sislojas', 'Cfg', 'sistema.cfg')), 'o tar.exe do Windows abre o .tar.zst');
    } catch (e) {
      confere(false, `o tar.exe do Windows abre o .tar.zst (${String(e.stderr || e.message).trim().slice(0, 200)})`);
    }
    confere(sombrasDoSistema() <= sombrasAntes, 'a cópia de sombra foi apagada no fim');
  } finally {
    trava.kill();
  }
}

// ------------------------------------------------------------------ 2. retomada (o coletor morto no meio)
console.log('Retomada: o coletor é fechado no meio e o mesmo comando continua');
{
  const { c, chave, link } = await novaColeta();
  let pedacos = 0;
  let r1 = null;
  let morto = null;
  // no 2º pedaço gravado, quem recebe mata o coletor antes de pedir o próximo (sem a confirmação, ele não avança)
  const recebendo = receptor(c.id, meuComputador, c.nome, chave, async () => {
    pedacos++;
    if (pedacos === 2) {
      execFileSync('taskkill', ['/PID', String(r1.filho.pid), '/T', '/F'], { stdio: 'ignore' });
      await r1.fim;
      morto();
    }
  });
  const extra = `-Pasta '${origem}' -Tudo${admin ? '' : ' -SemSombra'}`;
  const foiMorto = new Promise((r) => { morto = r; });
  r1 = rodarColetor(link, extra, path.join(trabalho, 'coletor-2a.log'));
  await foiMorto;
  const meio = await api('GET', `/coletas/${c.id}`);
  confere(meio.situacao === 'RECEBENDO' && meio.proximoSeq >= 2, `parou no meio, com ${meio.proximoSeq} pedaços gravados`);
  const r2 = rodarColetor(link, extra, path.join(trabalho, 'coletor-2b.log'));
  const codigo = await r2.fim;
  const { coleta } = await recebendo;
  const log = fs.readFileSync(path.join(trabalho, 'coletor-2b.log'), 'utf8');
  confere(codigo === 0 && /Continuando de onde parou/.test(log), `o mesmo comando continuou de onde parou (código ${codigo})`);
  if (codigo !== 0) console.log(log.split('\n').slice(-25).join('\n'));
  confere(coleta.situacao === 'INTEGRA', `conferido: ${coleta.conferencia?.mensagem}`);
  const alvo = path.join(trabalho, 'extraido-2');
  await extrairNode(meuComputador, c.nome, alvo);
  confere(iguais(listaDe(origem), listaDe(path.join(alvo, 'sislojas'))), 'extraído idêntico à origem');
  if (admin) confere(sombrasDoSistema() <= sombrasAntes, 'a cópia de sombra esquecida pelo coletor morto foi apagada na volta');
  const sessoes = path.join(process.env.LOCALAPPDATA, 'PlumbColetor', 'sessoes');
  const idSessao = crypto.createHash('sha256').update(c.token).digest('hex').slice(0, 24);
  confere(!fs.existsSync(path.join(sessoes, `${idSessao}.txt`)), 'a sessão guardada no cliente foi apagada no fim');
}

// ------------------------------------------------------------------ 3. sem a cópia de sombra
console.log('Sem a cópia de sombra: o .cfg travado vai para os não lidos, com o motivo');
{
  const trava = await travar(travado);
  try {
    const { c, chave, link } = await novaColeta();
    const recebendo = receptor(c.id, meuComputador, c.nome, chave);
    const { fim } = rodarColetor(link, `-Pasta '${origem}' -Tudo -SemSombra`, path.join(trabalho, 'coletor-3.log'));
    const codigo = await fim;
    const { coleta } = await recebendo;
    const log = fs.readFileSync(path.join(trabalho, 'coletor-3.log'), 'utf8');
    confere(codigo === 0, `o coletor terminou (código ${codigo})`);
    const motivo = (coleta.naoLidos || []).find((x) => x.includes('em-uso.cfg')) || '';
    confere(/travado/.test(motivo) && /administrador/.test(motivo), `o travado está nos não lidos com o motivo (${motivo})`);
    confere(/Ficaram de fora/.test(log) && log.includes('em-uso.cfg'), 'o coletor avisa que ficaram arquivos de fora');
    confere(coleta.situacao === 'INTEGRA', `o resto conferido: ${coleta.conferencia?.mensagem}`);
  } finally {
    trava.kill();
  }
}

console.log(falhas ? `\n${falhas} FALHA(S)` : '\nTudo certo.');
process.exit(falhas ? 1 : 0);
