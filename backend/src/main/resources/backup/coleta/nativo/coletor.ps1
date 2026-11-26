<#
  Coletor de backup do Plumb (Backups de clientes), para rodar no computador do cliente.

  Faz o mesmo que copiar a pasta do sistema, compactar e mandar, só que direto e conferido:
    1. tira uma cópia de sombra do disco (VSS, com o PowerShell como administrador): os arquivos em uso pelo sistema
       (.cfg, .dat abertos, travados) vêm inteiros e consistentes, como se o sistema estivesse fechado;
    2. lê a pasta inteira, calcula o SHA-256 de cada arquivo e compacta com o zstd nativo, em todos os núcleos;
    3. cifra cada pedaço com a chave do link (AES-256-GCM: nem o túnel nem o sistema leem o backup) e envia em
       paralelo pelo túnel, para o computador de quem gerou o link;
    4. no fim, quem recebe confere cada arquivo pelo SHA-256.
  Se cair no meio, rode o MESMO comando de novo: continua de onde parou.

  Uso (o comando vem pronto na tela Backups de clientes):
    ... & ([scriptblock]::Create(...)) -Link 'https://<túnel>/c/<token>#k=<chave>' [-Pasta C:\sislojas]
  Opções: -Pasta (sem perguntar), -Tudo (leva todas as pastas sem perguntar), -Nivel (zstd, 1 a 19; padrão 9),
  -Envios (envios em paralelo; padrão 4), -SemSombra (não usa a cópia de sombra).
#>
param(
    [Parameter(Mandatory = $true)][string] $Link,
    [string] $Pasta,
    [switch] $Tudo,
    [ValidateRange(1, 19)][int] $Nivel = 9,
    [ValidateRange(1, 8)][int] $Envios = 4,
    [switch] $SemSombra
)

$ErrorActionPreference = 'Stop'
$VersaoColetor = '1.0'
# A libzstd.dll servida pelo sistema (Zstandard 1.5.7 x64): conferida antes de carregar
$ShaDaDll = '287e7e474961e8886a3d961e2c07da647a7ac8d60d97023545f3a3ecf604daba'

function Titulo([string] $t) { Write-Host ''; Write-Host $t -ForegroundColor Cyan }
function Ok([string] $t) { Write-Host "  $t" -ForegroundColor Green }
function Info([string] $t) { Write-Host "  $t" }
function Aviso([string] $t) { Write-Host "  $t" -ForegroundColor Yellow }
# (sem "exit": colado no PowerShell, o exit fecharia a janela junto com a mensagem)
function Falha([string] $t) { Write-Host ''; Write-Host "  $t" -ForegroundColor Red; Write-Host ''; throw 'PLUMB_PAROU' }
function Parar { throw 'PLUMB_PAROU' }

function Formatar-Bytes([double] $n) {
    $u = 'B', 'KB', 'MB', 'GB', 'TB'; $i = 0
    while ($n -ge 1024 -and $i -lt 4) { $n /= 1024; $i++ }
    if ($i -eq 0) { return "$n B" }
    return ('{0:N1} {1}' -f $n, $u[$i])
}

[Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
$codigo = 1
try {

# ---------------------------------------------------------------- 64 bits
if (-not [Environment]::Is64BitOperatingSystem) {
    Falha ('Este Windows é de 32 bits: o coletor precisa de 64 bits. Use o link pelo Chrome/Edge (a página de coleta).')
}
if (-not [Environment]::Is64BitProcess) {
    # PowerShell de 32 bits num Windows de 64: roda de novo no de 64
    $copia = Join-Path $env:TEMP 'plumb-coletor.ps1'
    [IO.File]::WriteAllText($copia, $MyInvocation.MyCommand.ScriptBlock.ToString(), (New-Object Text.UTF8Encoding $true))
    $argumentos = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $copia, '-Link', $Link, '-Nivel', $Nivel, '-Envios', $Envios)
    if ($Pasta) { $argumentos += @('-Pasta', $Pasta) }
    if ($Tudo) { $argumentos += '-Tudo' }
    if ($SemSombra) { $argumentos += '-SemSombra' }
    & "$env:windir\sysnative\WindowsPowerShell\v1.0\powershell.exe" @argumentos
    $codigo = $LASTEXITCODE
    return
}

# ---------------------------------------------------------------- o link
$m = [regex]::Match($Link.Trim(), '^(https?://[^/#?]+)/c/([A-Za-z0-9_-]{43})/?(?:\?[^#]*)?#(?:.*&)?k=([A-Za-z0-9_-]{43})(?:&|$)')
if (-not $m.Success) {
    Falha 'Link inválido: copie o comando inteiro de novo na tela Backups de clientes (ele precisa da parte depois do "#").'
}
$Base = $m.Groups[1].Value
$Token = $m.Groups[2].Value
$TextoDaChave = $m.Groups[3].Value
$Chave = [Convert]::FromBase64String(($TextoDaChave.Replace('-', '+').Replace('_', '/')) + '=')
if ($Chave.Length -ne 32) { Falha 'A chave do link está estragada: copie o comando de novo.' }

$PastaDoColetor = Join-Path $env:LOCALAPPDATA 'PlumbColetor'
New-Item -ItemType Directory -Force -Path $PastaDoColetor | Out-Null

Write-Host ''
Write-Host "  Coletor de backup do Plumb $VersaoColetor" -ForegroundColor White

# ---------------------------------------------------------------- zstd (DLL do sistema, conferida)
$dll = Join-Path $PastaDoColetor 'zstd-1.5.7\libzstd.dll'
$precisaBaixar = $true
if (Test-Path -LiteralPath $dll) {
    $precisaBaixar = (Get-FileHash -LiteralPath $dll -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ShaDaDll
}
if ($precisaBaixar) {
    New-Item -ItemType Directory -Force -Path (Split-Path $dll) | Out-Null
    $temporario = "$dll.baixando"
    try {
        (New-Object Net.WebClient).DownloadFile("$Base/c/_/nativo/libzstd.dll", $temporario)
    } catch {
        Falha "Não consegui baixar o compactador do sistema ($Base): $($_.Exception.Message)"
    }
    if ((Get-FileHash -LiteralPath $temporario -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ShaDaDll) {
        Remove-Item -LiteralPath $temporario -Force
        Falha 'O compactador baixado não confere (SHA-256 diferente): nada foi feito.'
    }
    Move-Item -LiteralPath $temporario -Destination $dll -Force
}

# ---------------------------------------------------------------- o motor (C#, compilado aqui pelo .NET do Windows)
if (-not ('PlumbColetor.Coletor' -as [type])) {
Add-Type -ReferencedAssemblies 'System', 'System.Core', 'System.Web.Extensions' -TypeDefinition @'
using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace PlumbColetor
{
    public sealed class Entrada
    {
        public string Caminho;   // no .tar: <raiz>/<relativo>, com "/"
        public string Leitura;   // onde ler (pode ser dentro da cópia de sombra)
        public string Topo;      // pasta de primeiro nível; null = a raiz; "__arquivos-da-raiz__" = arquivo solto nela
        public bool Pasta;
        public long Tamanho;
        public long Modificado;  // milissegundos desde 1970
    }

    public sealed class NaoLido
    {
        public string Caminho;
        public string Motivo;
    }

    public sealed class Listagem
    {
        public List<Entrada> Entradas = new List<Entrada>();
        public List<NaoLido> NaoLidos = new List<NaoLido>();
    }

    public static class Nativo
    {
        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr LoadLibrary(string caminho);

        /** Carrega a libzstd.dll baixada (as chamadas ao zstd usam esta, já na memória). */
        public static string Carregar(string caminho)
        {
            if (LoadLibrary(caminho) == IntPtr.Zero)
            {
                throw new IOException("Não consegui carregar " + caminho + " (erro " + Marshal.GetLastWin32Error() + ")");
            }
            uint v = Zstd.ZSTD_versionNumber();
            return (v / 10000) + "." + (v / 100 % 100) + "." + (v % 100);
        }
    }

    public static class Lista
    {
        public const string RaizSoltos = "__arquivos-da-raiz__";
        static readonly DateTime Epoca = new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc);

        public static long Ms(DateTime utc)
        {
            return (long)(utc - Epoca).TotalMilliseconds;
        }

        /** Caminho com o prefixo \\?\ (passa dos 260 caracteres). */
        public static string Longo(string p)
        {
            if (p.StartsWith(@"\\?\")) return p;
            if (p.StartsWith(@"\\")) return @"\\?\UNC\" + p.Substring(2);
            return @"\\?\" + p;
        }

        /** Percorre a pasta (sem ler conteúdo), em ordem: o mesmo que a página de coleta lista. */
        public static Listagem Listar(string raizLeitura, string raizNome)
        {
            Listagem r = new Listagem();
            Entrada raiz = new Entrada();
            raiz.Caminho = raizNome;
            raiz.Leitura = raizLeitura;
            raiz.Pasta = true;
            r.Entradas.Add(raiz);
            Stack<string[]> pilha = new Stack<string[]>();
            pilha.Push(new string[] { raizLeitura, "" });
            while (pilha.Count > 0)
            {
                string[] atual = pilha.Pop();
                List<FileSystemInfo> itens = new List<FileSystemInfo>();
                try
                {
                    foreach (FileSystemInfo i in new DirectoryInfo(Longo(atual[0])).EnumerateFileSystemInfos()) itens.Add(i);
                }
                catch (Exception e)
                {
                    NaoLido n = new NaoLido();
                    n.Caminho = atual[1].Length == 0 ? raizNome : raizNome + "/" + atual[1];
                    n.Motivo = "pasta não pôde ser listada (" + e.Message + ")";
                    r.NaoLidos.Add(n);
                    continue;
                }
                foreach (FileSystemInfo info in itens)
                {
                    bool pasta = (info.Attributes & FileAttributes.Directory) != 0;
                    string rel = atual[1].Length == 0 ? info.Name : atual[1] + "/" + info.Name;
                    if (pasta && (info.Attributes & FileAttributes.ReparsePoint) != 0)
                    {
                        NaoLido n = new NaoLido();
                        n.Caminho = raizNome + "/" + rel;
                        n.Motivo = "atalho de pasta (junção): não seguido";
                        r.NaoLidos.Add(n);
                        continue;
                    }
                    Entrada e = new Entrada();
                    e.Caminho = raizNome + "/" + rel;
                    e.Leitura = Path.Combine(atual[0], info.Name);
                    e.Topo = atual[1].Length == 0 ? (pasta ? info.Name : RaizSoltos) : atual[1].Split('/')[0];
                    e.Pasta = pasta;
                    try { e.Modificado = Ms(info.LastWriteTimeUtc); } catch { e.Modificado = 0; }
                    if (pasta)
                    {
                        pilha.Push(new string[] { e.Leitura, rel });
                    }
                    else
                    {
                        try { e.Tamanho = ((FileInfo)info).Length; } catch { e.Tamanho = 0; }
                    }
                    r.Entradas.Add(e);
                }
            }
            r.Entradas.Sort(delegate (Entrada a, Entrada b) { return string.CompareOrdinal(a.Caminho, b.Caminho); });
            return r;
        }
    }

    /** Cabeçalhos .tar (ustar, com PAX para acento, caminho longo ou arquivo de 8 GiB ou mais): os mesmos de tar.js. */
    static class Tar
    {
        static readonly Encoding Utf8 = new UTF8Encoding(false);
        const long MaxOctal11 = 8589934591L;

        static void Texto(byte[] b, int o, int t, string s)
        {
            byte[] x = Utf8.GetBytes(s);
            Buffer.BlockCopy(x, 0, b, o, Math.Min(t, x.Length));
        }

        static void Octal(byte[] b, int o, int t, long v)
        {
            Texto(b, o, t - 1, Convert.ToString(v, 8).PadLeft(t - 1, '0'));
            b[o + t - 1] = 0;
        }

        static bool Ascii(string s)
        {
            foreach (char c in s) if (c < 0x20 || c > 0x7e) return false;
            return true;
        }

        static string SoAscii(string s)
        {
            StringBuilder sb = new StringBuilder(s.Length);
            foreach (char c in s) sb.Append(c < 0x20 || c > 0x7e ? '_' : c);
            return sb.ToString();
        }

        static byte[] Ustar(string nome, long tamanho, long mtime, char tipo)
        {
            byte[] b = new byte[512];
            Texto(b, 0, 100, nome);
            Octal(b, 100, 8, tipo == '5' ? 493 : 420);
            Octal(b, 108, 8, 0);
            Octal(b, 116, 8, 0);
            Octal(b, 124, 12, tamanho);
            Octal(b, 136, 12, mtime);
            for (int i = 148; i < 156; i++) b[i] = 0x20;
            b[156] = (byte)tipo;
            Texto(b, 257, 6, "ustar");
            b[262] = 0;
            Texto(b, 263, 2, "00");
            int soma = 0;
            for (int i = 0; i < 512; i++) soma += b[i];
            Texto(b, 148, 6, Convert.ToString(soma, 8).PadLeft(6, '0'));
            b[154] = 0;
            b[155] = 0x20;
            return b;
        }

        static string RegistroPax(string chave, string valor)
        {
            string corpo = " " + chave + "=" + valor + "\n";
            int tc = Utf8.GetByteCount(corpo);
            int t = tc + 1;
            while (t.ToString().Length + tc != t) t = t.ToString().Length + tc;
            return t + corpo;
        }

        public static int Preenchimento(long n)
        {
            int r = (int)(n % 512);
            return r == 0 ? 0 : 512 - r;
        }

        public static byte[] Cabecalho(string caminho, bool pasta, long tamanho, long modificadoMs)
        {
            char tipo = pasta ? '5' : '0';
            string nome = pasta && !caminho.EndsWith("/") ? caminho + "/" : caminho;
            long mtime = Math.Max(0, modificadoMs / 1000);
            long real = pasta ? 0 : tamanho;
            bool pax = !Ascii(nome) || Utf8.GetByteCount(nome) > 100 || real > MaxOctal11;
            if (!pax) return Ustar(nome, real, mtime, tipo);
            string regs = RegistroPax("path", nome);
            if (real > MaxOctal11) regs += RegistroPax("size", real.ToString());
            byte[] dadosPax = Utf8.GetBytes(regs);
            string curto = SoAscii(nome);
            if (curto.Length > 90) curto = curto.Substring(curto.Length - 90);
            string nomePax = "PaxHeaders/" + curto;
            if (nomePax.Length > 99) nomePax = nomePax.Substring(0, 99);
            int totalPax = 512 + dadosPax.Length + Preenchimento(dadosPax.Length);
            byte[] saida = new byte[totalPax + 512];
            Buffer.BlockCopy(Ustar(nomePax, dadosPax.Length, mtime, 'x'), 0, saida, 0, 512);
            Buffer.BlockCopy(dadosPax, 0, saida, 512, dadosPax.Length);
            Buffer.BlockCopy(Ustar(curto, real > MaxOctal11 ? 0 : real, mtime, tipo), 0, saida, totalPax, 512);
            return saida;
        }
    }

    sealed class Acumulador
    {
        public byte[] Dados;
        public int Tamanho;

        public Acumulador(int capacidade) { Dados = new byte[capacidade]; }

        public void Garantir(int extra)
        {
            if (Tamanho + extra <= Dados.Length) return;
            byte[] maior = new byte[Math.Max(Dados.Length * 2, Tamanho + extra)];
            Buffer.BlockCopy(Dados, 0, maior, 0, Tamanho);
            Dados = maior;
        }

        public void Adicionar(byte[] b) { Adicionar(b, 0, b.Length); }

        public void Adicionar(byte[] b, int o, int n)
        {
            Garantir(n);
            Buffer.BlockCopy(b, o, Dados, Tamanho, n);
            Tamanho += n;
        }

        public void Zeros(int n)
        {
            if (n <= 0) return;
            Garantir(n);
            Array.Clear(Dados, Tamanho, n);
            Tamanho += n;
        }

        public byte[] Retirar()
        {
            byte[] s = new byte[Tamanho];
            Buffer.BlockCopy(Dados, 0, s, 0, Tamanho);
            Tamanho = 0;
            return s;
        }
    }

    public static class Zstd
    {
        [DllImport("libzstd.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern uint ZSTD_versionNumber();
        [DllImport("libzstd.dll", CallingConvention = CallingConvention.Cdecl)]
        static extern UIntPtr ZSTD_compressBound(UIntPtr n);
        [DllImport("libzstd.dll", CallingConvention = CallingConvention.Cdecl)]
        static extern UIntPtr ZSTD_compress(byte[] destino, UIntPtr capacidade, byte[] origem, UIntPtr tamanho, int nivel);
        [DllImport("libzstd.dll", CallingConvention = CallingConvention.Cdecl)]
        static extern uint ZSTD_isError(UIntPtr codigo);
        [DllImport("libzstd.dll", CallingConvention = CallingConvention.Cdecl)]
        static extern IntPtr ZSTD_getErrorName(UIntPtr codigo);

        /** Um quadro zstd independente (com o tamanho original no cabeçalho, que quem recebe usa). */
        public static byte[] Compactar(byte[] origem, int nivel, out int tamanho)
        {
            int limite = (int)ZSTD_compressBound(new UIntPtr((uint)origem.Length)).ToUInt64();
            byte[] destino = new byte[limite];
            UIntPtr r = ZSTD_compress(destino, new UIntPtr((uint)limite), origem, new UIntPtr((uint)origem.Length), nivel);
            if (ZSTD_isError(r) != 0) throw new IOException("zstd: " + Marshal.PtrToStringAnsi(ZSTD_getErrorName(r)));
            tamanho = (int)r.ToUInt64();
            return destino;
        }
    }

    /** AES-256-GCM pelo CNG do Windows: o mesmo formato da página (12 bytes de vetor + cifrado + etiqueta de 16). */
    public sealed class Cifra : IDisposable
    {
        [StructLayout(LayoutKind.Sequential)]
        struct InfoGcm
        {
            public int cbSize;
            public int dwInfoVersion;
            public IntPtr pbNonce;
            public int cbNonce;
            public IntPtr pbAuthData;
            public int cbAuthData;
            public IntPtr pbTag;
            public int cbTag;
            public IntPtr pbMacContext;
            public int cbMacContext;
            public int cbAAD;
            public long cbData;
            public int dwFlags;
        }

        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        static extern int BCryptOpenAlgorithmProvider(out IntPtr algoritmo, string id, string implementacao, int flags);
        [DllImport("bcrypt.dll", CharSet = CharSet.Unicode)]
        static extern int BCryptSetProperty(IntPtr objeto, string propriedade, byte[] valor, int tamanho, int flags);
        [DllImport("bcrypt.dll")]
        static extern int BCryptGenerateSymmetricKey(IntPtr algoritmo, out IntPtr chave, IntPtr objeto, int tamanhoObjeto, byte[] segredo, int tamanhoSegredo, int flags);
        [DllImport("bcrypt.dll")]
        static extern int BCryptEncrypt(IntPtr chave, IntPtr entrada, int tamanhoEntrada, ref InfoGcm info, IntPtr iv, int tamanhoIv, IntPtr saida, int tamanhoSaida, out int escrito, int flags);
        [DllImport("bcrypt.dll")]
        static extern int BCryptDestroyKey(IntPtr chave);
        [DllImport("bcrypt.dll")]
        static extern int BCryptCloseAlgorithmProvider(IntPtr algoritmo, int flags);

        const string PrefixoAad = "backup-clientes:";
        readonly IntPtr algoritmo;
        readonly IntPtr chave;
        readonly object trava = new object();
        readonly RandomNumberGenerator aleatorio = RandomNumberGenerator.Create();

        public Cifra(byte[] segredo)
        {
            int s = BCryptOpenAlgorithmProvider(out algoritmo, "AES", null, 0);
            if (s != 0) throw new CryptographicException("AES indisponível (0x" + s.ToString("X8") + ")");
            byte[] modo = Encoding.Unicode.GetBytes("ChainingModeGCM\0");
            s = BCryptSetProperty(algoritmo, "ChainingMode", modo, modo.Length, 0);
            if (s != 0) throw new CryptographicException("GCM indisponível (0x" + s.ToString("X8") + ")");
            s = BCryptGenerateSymmetricKey(algoritmo, out chave, IntPtr.Zero, 0, segredo, segredo.Length, 0);
            if (s != 0) throw new CryptographicException("Chave inválida (0x" + s.ToString("X8") + ")");
        }

        /** iv + cifrado + etiqueta; o número do pedaço é dado autenticado (trocar de lugar não passa). */
        public byte[] Cifrar(byte[] dados, int tamanho, long seq)
        {
            byte[] iv = new byte[12];
            lock (aleatorio) aleatorio.GetBytes(iv);
            byte[] aad = Encoding.ASCII.GetBytes(PrefixoAad + seq);
            byte[] etiqueta = new byte[16];
            byte[] saida = new byte[12 + tamanho + 16];
            Buffer.BlockCopy(iv, 0, saida, 0, 12);
            GCHandle hIv = GCHandle.Alloc(iv, GCHandleType.Pinned);
            GCHandle hAad = GCHandle.Alloc(aad, GCHandleType.Pinned);
            GCHandle hEtiqueta = GCHandle.Alloc(etiqueta, GCHandleType.Pinned);
            GCHandle hEntrada = GCHandle.Alloc(dados, GCHandleType.Pinned);
            GCHandle hSaida = GCHandle.Alloc(saida, GCHandleType.Pinned);
            try
            {
                InfoGcm info = new InfoGcm();
                info.cbSize = Marshal.SizeOf(typeof(InfoGcm));
                info.dwInfoVersion = 1;
                info.pbNonce = hIv.AddrOfPinnedObject();
                info.cbNonce = 12;
                info.pbAuthData = hAad.AddrOfPinnedObject();
                info.cbAuthData = aad.Length;
                info.pbTag = hEtiqueta.AddrOfPinnedObject();
                info.cbTag = 16;
                int escrito;
                int s;
                lock (trava)
                {
                    s = BCryptEncrypt(chave, hEntrada.AddrOfPinnedObject(), tamanho, ref info, IntPtr.Zero, 0,
                        IntPtr.Add(hSaida.AddrOfPinnedObject(), 12), tamanho, out escrito, 0);
                }
                if (s != 0) throw new CryptographicException("Não cifrou (0x" + s.ToString("X8") + ")");
            }
            finally
            {
                hIv.Free(); hAad.Free(); hEtiqueta.Free(); hEntrada.Free(); hSaida.Free();
            }
            Buffer.BlockCopy(etiqueta, 0, saida, 12 + tamanho, 16);
            return saida;
        }

        public void Dispose()
        {
            BCryptDestroyKey(chave);
            BCryptCloseAlgorithmProvider(algoritmo, 0);
        }
    }

    public sealed class Resposta
    {
        public int Status;
        public string Texto;
        public Dictionary<string, object> Json;
        public string Codigo { get { return Valor("codigo"); } }
        public string Mensagem { get { return Valor("mensagem"); } }

        string Valor(string chave)
        {
            object v;
            return Json != null && Json.TryGetValue(chave, out v) && v != null ? v.ToString() : null;
        }
    }

    public sealed class Api
    {
        public static readonly JavaScriptSerializer Json = Criar();
        readonly string raiz;
        readonly string sessao;

        static JavaScriptSerializer Criar()
        {
            JavaScriptSerializer j = new JavaScriptSerializer();
            j.MaxJsonLength = int.MaxValue;
            return j;
        }

        public Api(string baseUrl, string token, string sessao)
        {
            raiz = baseUrl + "/c/" + token + "/api/";
            this.sessao = sessao;
            ServicePointManager.DefaultConnectionLimit = 32;
            ServicePointManager.Expect100Continue = false;
        }

        public Resposta Pedir(string metodo, string caminho, byte[] corpo, string tipo, int tempoMs)
        {
            HttpWebRequest req = (HttpWebRequest)WebRequest.Create(raiz + caminho);
            req.Method = metodo;
            req.Timeout = tempoMs;
            req.ReadWriteTimeout = tempoMs;
            req.UserAgent = "PlumbColetor/1.0";
            req.Headers["X-Coleta-Sessao"] = sessao;
            req.Headers[HttpRequestHeader.CacheControl] = "no-store";
            if (corpo != null)
            {
                req.ContentType = tipo;
                req.ContentLength = corpo.Length;
                using (Stream s = req.GetRequestStream()) s.Write(corpo, 0, corpo.Length);
            }
            HttpWebResponse resp;
            try
            {
                resp = (HttpWebResponse)req.GetResponse();
            }
            catch (WebException e)
            {
                resp = e.Response as HttpWebResponse;
                if (resp == null) throw;
            }
            using (resp)
            {
                Resposta r = new Resposta();
                r.Status = (int)resp.StatusCode;
                using (StreamReader leitor = new StreamReader(resp.GetResponseStream(), Encoding.UTF8)) r.Texto = leitor.ReadToEnd();
                if (r.Texto.StartsWith("{"))
                {
                    try { r.Json = Json.DeserializeObject(r.Texto) as Dictionary<string, object>; } catch { r.Json = null; }
                }
                return r;
            }
        }

        public Resposta Json_(string metodo, string caminho, object corpo, int tempoMs)
        {
            byte[] b = corpo == null ? null : Encoding.UTF8.GetBytes(Json.Serialize(corpo));
            return Pedir(metodo, caminho, b, "application/json", tempoMs);
        }

        /** GET da coleta, em texto JSON (para o PowerShell). */
        public string InfoTexto()
        {
            Resposta r = Pedir("GET", "coleta", null, null, 60000);
            if (r.Status != 200) throw new IOException(r.Mensagem ?? ("O sistema respondeu " + r.Status));
            return r.Texto;
        }
    }

    public sealed class Bloco
    {
        public long Seq;
        public byte[] Bruto;
        public Dictionary<string, object> Fim;
        public List<object> Arquivos;
        public List<object> NaoLidos;
    }

    public sealed class Coletor
    {
        // ---- entrada (o PowerShell preenche)
        public string Base, Token, Sessao, RaizNome, Descricao;
        public byte[] Chave;
        public int Nivel = 6;
        public int Compactadores = 2;
        public int Envios = 4;
        public List<Entrada> Entradas;
        public List<NaoLido> NaoLidosIniciais;
        public List<string> Pastas;

        // ---- estado
        readonly object trava = new object();
        Api api;
        Cifra cifra;
        volatile bool cancelado;
        volatile string fatal;
        string geracao;
        long tamanhoBloco;
        long totalArquivos, totalBytes;
        long lidoBytes, lidoArquivos, brutoProduzido, compactadoBytes, enviadoBytes;
        long produzidos;          // o próximo número de pedaço a produzir
        long proximoAEnviar;
        long ack;                 // o próximo que o sistema espera (todos antes dele, gravados por quem recebe)
        bool producaoTerminou;
        int alterados;
        readonly List<string> deFora = new List<string>();

        void FicouDeFora(List<object> naoLidos, string caminho, string motivo)
        {
            naoLidos.Add(Item(caminho, motivo));
            lock (deFora) deFora.Add(caminho + " — " + motivo);
        }
        string arquivoAtual = "";
        string aviso = "";
        readonly Dictionary<long, byte[]> prontos = new Dictionary<long, byte[]>();
        SemaphoreSlim vagas;
        BlockingCollection<Bloco> fila;
        DateTime inicio;
        readonly Queue<long[]> amostras = new Queue<long[]>();

        static readonly HashSet<string> Fatais = new HashSet<string>(new string[] {
            "GERACAO_ANTIGA", "CANCELADA", "VENCIDO", "LINK_INVALIDO", "JA_CONCLUIDA", "OUTRA_SESSAO", "COLETA_GRANDE",
            "PEDACO_SEM_CIFRA", "SEM_SESSAO", "PEDACO_INVALIDO", "PEDACO_GRANDE" });

        public void Cancelar() { cancelado = true; }

        static long Long(object v) { return v == null ? 0 : Convert.ToInt64(v); }

        /** 0 = enviado e conferido (ou entregue); 1 = falhou; 2 = interrompido. */
        public int Executar()
        {
            api = new Api(Base, Token, Sessao);
            cifra = new Cifra(Chave);
            ConsoleCancelEventHandler aoCancelar = delegate (object o, ConsoleCancelEventArgs e) { e.Cancel = true; cancelado = true; };
            Console.CancelKeyPress += aoCancelar;
            try
            {
                return Coletar();
            }
            finally
            {
                Console.CancelKeyPress -= aoCancelar;
                cifra.Dispose();
            }
        }

        int Coletar()
        {
            foreach (Entrada e in Entradas)
            {
                if (!e.Pasta) { totalArquivos++; totalBytes += e.Tamanho; }
            }
            byte[] g = new byte[12];
            using (RandomNumberGenerator r = RandomNumberGenerator.Create()) r.GetBytes(g);
            geracao = BitConverter.ToString(g).Replace("-", "").ToLowerInvariant();

            Dictionary<string, object> info = Geracao(null);
            if (info == null) return 1;
            tamanhoBloco = Long(info["tamanhoBloco"]);
            object cursorObj;
            Dictionary<string, object> cursor = info.TryGetValue("cursor", out cursorObj) ? cursorObj as Dictionary<string, object> : null;
            if (cursor != null && Long(Pegar(cursor, "offset")) > 0)
            {
                // parou no meio de um arquivo: volta para o começo dele (o coletor só retoma entre arquivos)
                info = Geracao(Pegar(cursor, "caminho") as string);
                if (info == null) return 1;
                cursor = info.TryGetValue("cursor", out cursorObj) ? cursorObj as Dictionary<string, object> : null;
            }
            ack = Long(info["proximoSeq"]);
            produzidos = ack;
            proximoAEnviar = ack;
            enviadoBytes = Long(info["bytesRecebidos"]);

            int indice = 0;
            bool soConcluir = false;
            if (cursor != null)
            {
                if (Pegar(cursor, "fimDaLista") is bool && (bool)Pegar(cursor, "fimDaLista")) soConcluir = true;
                string c = Pegar(cursor, "caminho") as string;
                if (c != null)
                {
                    while (indice < Entradas.Count && string.CompareOrdinal(Entradas[indice].Caminho, c) < 0) indice++;
                }
                for (int i = 0; i < indice; i++)
                {
                    if (!Entradas[i].Pasta) { lidoBytes += Entradas[i].Tamanho; lidoArquivos++; }
                }
                if (ack > 0) Console.WriteLine("  Continuando de onde parou: pedaço " + ack + ", " + Formatar(enviadoBytes) + " já entregues.");
            }

            inicio = DateTime.UtcNow;
            vagas = new SemaphoreSlim(Envios * 2 + Compactadores * 2 + 2);
            fila = new BlockingCollection<Bloco>(Compactadores * 2);
            List<Thread> threads = new List<Thread>();
            Thread leitor = new Thread(delegate () { Proteger(delegate () { if (!soConcluir) Ler(indice, ack == 0 ? NaoLidosIniciais : null); }); FimDaLeitura(); });
            leitor.IsBackground = true;
            leitor.Start();
            for (int i = 0; i < Compactadores; i++)
            {
                Thread t = new Thread(delegate () { Proteger(Compactar); });
                t.IsBackground = true;
                t.Start();
                threads.Add(t);
            }
            for (int i = 0; i < Envios; i++)
            {
                Thread t = new Thread(delegate () { Proteger(Enviar); });
                t.IsBackground = true;
                t.Start();
                threads.Add(t);
            }
            DateTime ultimoProgresso = DateTime.MinValue;
            while (true)
            {
                bool vivos = false;
                foreach (Thread t in threads) if (t.IsAlive) vivos = true;
                if (!vivos || fatal != null || cancelado) break;
                Desenhar();
                if ((DateTime.UtcNow - ultimoProgresso).TotalSeconds >= 10)
                {
                    ultimoProgresso = DateTime.UtcNow;
                    Progresso();
                }
                Thread.Sleep(500);
            }
            Desenhar();
            Console.WriteLine();
            if (fatal != null)
            {
                cancelado = true;
                Console.WriteLine();
                Console.ForegroundColor = ConsoleColor.Red;
                Console.WriteLine("  " + fatal);
                Console.ResetColor();
                return 1;
            }
            if (cancelado)
            {
                Console.WriteLine();
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("  Interrompido. Rode o mesmo comando de novo para continuar de onde parou.");
                Console.ResetColor();
                return 2;
            }
            return Concluir();
        }

        static object Pegar(Dictionary<string, object> d, string chave)
        {
            object v;
            return d != null && d.TryGetValue(chave, out v) ? v : null;
        }

        void Proteger(ThreadStart acao)
        {
            try
            {
                acao();
            }
            catch (Exception e)
            {
                if (fatal == null) fatal = "Falhou: " + e.Message;
                lock (trava) Monitor.PulseAll(trava);
            }
        }

        Dictionary<string, object> Geracao(string voltarAoInicioDe)
        {
            Dictionary<string, object> pedido = new Dictionary<string, object>();
            pedido["geracao"] = geracao;
            pedido["raiz"] = RaizNome;
            pedido["pastas"] = Pastas;
            pedido["computador"] = Environment.MachineName;
            pedido["navegador"] = Descricao;
            pedido["totalArquivos"] = totalArquivos;
            pedido["totalBytes"] = totalBytes;
            pedido["voltarAoInicioDe"] = voltarAoInicioDe;
            int pausa = 1000;
            while (!cancelado)
            {
                try
                {
                    Resposta r = api.Json_("POST", "geracao", pedido, 60000);
                    if (r.Status == 200) return r.Json;
                    if (r.Status < 500 && r.Status != 408 && r.Status != 429)
                    {
                        fatal = r.Mensagem ?? ("O sistema recusou o começo da coleta (" + r.Status + ").");
                        return null;
                    }
                }
                catch (Exception e)
                {
                    Console.WriteLine("  Sem conexão com o sistema (" + e.Message + "); tentando de novo…");
                }
                Thread.Sleep(pausa);
                pausa = Math.Min(pausa * 2, 30000);
            }
            return null;
        }

        // ------------------------------------------------------------ leitura (as mesmas regras de corte de empacotador.js)

        Dictionary<string, object> ProximoCursor(int i)
        {
            Dictionary<string, object> c = new Dictionary<string, object>();
            if (i < Entradas.Count) { c["caminho"] = Entradas[i].Caminho; c["offset"] = 0; }
            else c["fimDaLista"] = true;
            return c;
        }

        void Produzir(Acumulador buffer, Dictionary<string, object> fim, ref List<object> arquivos, ref List<object> naoLidos)
        {
            while (!vagas.Wait(500))
            {
                if (cancelado || fatal != null) throw new OperationCanceledException();
            }
            Bloco b = new Bloco();
            b.Bruto = buffer.Retirar();
            b.Fim = fim;
            b.Arquivos = arquivos;
            b.NaoLidos = naoLidos;
            lock (trava)
            {
                b.Seq = produzidos++;
                brutoProduzido += b.Bruto.Length;
            }
            arquivos = new List<object>();
            naoLidos = new List<object>();
            fila.Add(b);
        }

        void FimDaLeitura()
        {
            fila.CompleteAdding();
            lock (trava)
            {
                producaoTerminou = true;
                arquivoAtual = "";
                Monitor.PulseAll(trava);
            }
        }

        static Dictionary<string, object> Item(string caminho, string motivo)
        {
            Dictionary<string, object> d = new Dictionary<string, object>();
            d["caminho"] = caminho;
            d["motivo"] = motivo;
            return d;
        }

        static string Motivo(Exception e)
        {
            if (e == null) return "não foi possível ler";
            int codigo = Marshal.GetHRForException(e) & 0xFFFF;
            if (e is FileNotFoundException || e is DirectoryNotFoundException) return "apagado durante a cópia";
            if (e is UnauthorizedAccessException) return "sem permissão de leitura";
            if (codigo == 32) return "em uso e travado pelo sistema (rode o coletor como administrador: ele usa a cópia de sombra)";
            if (codigo == 33) return "trecho travado pelo sistema (rode o coletor como administrador: ele usa a cópia de sombra)";
            return e.Message;
        }

        static FileStream Abrir(string caminho)
        {
            return new FileStream(Lista.Longo(caminho), FileMode.Open, FileAccess.Read,
                FileShare.ReadWrite | FileShare.Delete, 1 << 20, FileOptions.SequentialScan);
        }

        static int LerTudo(FileStream fs, byte[] destino, int deslocamento, int n)
        {
            int total = 0;
            while (total < n)
            {
                int r = fs.Read(destino, deslocamento + total, n - total);
                if (r <= 0) break;
                total += r;
            }
            return total;
        }

        static string Hex(byte[] b)
        {
            StringBuilder s = new StringBuilder(b.Length * 2);
            foreach (byte x in b) s.Append(x.ToString("x2"));
            return s.ToString();
        }

        void Ler(int indiceInicial, List<NaoLido> naoLidosIniciais)
        {
            int bloco = (int)tamanhoBloco;
            int metade = bloco / 2;
            Acumulador buffer = new Acumulador(bloco + metade + 4096);
            List<object> arquivos = new List<object>();
            List<object> naoLidos = new List<object>();
            if (naoLidosIniciais != null)
            {
                foreach (NaoLido n in naoLidosIniciais) FicouDeFora(naoLidos, n.Caminho, n.Motivo);
            }

            for (int indice = indiceInicial; indice < Entradas.Count; indice++)
            {
                if (cancelado || fatal != null) return;
                Entrada e = Entradas[indice];
                arquivoAtual = e.Caminho;
                if (e.Pasta)
                {
                    buffer.Adicionar(Tar.Cabecalho(e.Caminho, true, 0, e.Modificado));
                    Dictionary<string, object> p = new Dictionary<string, object>();
                    p["caminho"] = e.Caminho;
                    p["tipo"] = "pasta";
                    p["modificado"] = e.Modificado;
                    arquivos.Add(p);
                    if (buffer.Tamanho >= bloco) Produzir(buffer, ProximoCursor(indice + 1), ref arquivos, ref naoLidos);
                    continue;
                }

                // abre (com novas tentativas: travado por um instante)
                FileStream fs = null;
                Exception erro = null;
                for (int t = 0; t < 6 && fs == null; t++)
                {
                    try { fs = Abrir(e.Leitura); }
                    catch (Exception x) { erro = x; if (x is FileNotFoundException || x is UnauthorizedAccessException) break; Thread.Sleep(150 * (t + 1)); }
                }
                if (fs == null)
                {
                    FicouDeFora(naoLidos, e.Caminho, Motivo(erro));
                    continue;
                }
                using (fs)
                {
                    long tamanho = fs.Length;
                    long modificado = e.Modificado;
                    try { modificado = Lista.Ms(File.GetLastWriteTimeUtc(Lista.Longo(e.Leitura))); } catch { }
                    bool alterado = false;
                    using (SHA256 sha = new SHA256Cng())
                    {
                        if (tamanho <= metade)
                        {
                            // pequeno: inteiro num bloco
                            byte[] dados = new byte[tamanho];
                            int lido = 0;
                            Exception falha = null;
                            for (int t = 0; t < 6; t++)
                            {
                                try { fs.Position = 0; lido = LerTudo(fs, dados, 0, (int)tamanho); falha = null; break; }
                                catch (Exception x) { falha = x; Thread.Sleep(150 * (t + 1)); }
                            }
                            if (falha != null)
                            {
                                FicouDeFora(naoLidos, e.Caminho, Motivo(falha));
                                continue;
                            }
                            if (lido < tamanho || fs.Length != tamanho) alterado = true;
                            buffer.Adicionar(Tar.Cabecalho(e.Caminho, false, tamanho, modificado));
                            buffer.Adicionar(dados);
                            buffer.Zeros(Tar.Preenchimento(tamanho));
                            AdicionarArquivo(arquivos, e.Caminho, tamanho, modificado, Hex(sha.ComputeHash(dados)), alterado);
                            Interlocked.Add(ref lidoBytes, tamanho);
                            Interlocked.Increment(ref lidoArquivos);
                            if (buffer.Tamanho >= bloco) Produzir(buffer, ProximoCursor(indice + 1), ref arquivos, ref naoLidos);
                            continue;
                        }

                        // grande: começa num bloco novo (ponto de retomada no começo dele)
                        if (buffer.Tamanho > 0)
                        {
                            Dictionary<string, object> fimAntes = new Dictionary<string, object>();
                            fimAntes["caminho"] = e.Caminho;
                            fimAntes["offset"] = 0;
                            Produzir(buffer, fimAntes, ref arquivos, ref naoLidos);
                        }
                        buffer.Adicionar(Tar.Cabecalho(e.Caminho, false, tamanho, modificado));
                        long offset = 0;
                        bool trechoPerdido = false;
                        while (offset < tamanho)
                        {
                            if (cancelado || fatal != null) return;
                            int n = (int)Math.Min(bloco - buffer.Tamanho, tamanho - offset);
                            buffer.Garantir(n);
                            int lido = -1;
                            Exception falha = null;
                            for (int t = 0; t < 6 && lido < 0; t++)
                            {
                                try
                                {
                                    if (fs.Position != offset) fs.Position = offset;
                                    lido = LerTudo(fs, buffer.Dados, buffer.Tamanho, n);
                                }
                                catch (Exception x) { falha = x; alterado = true; Thread.Sleep(100 * (t + 1)); }
                            }
                            if (lido < 0)
                            {
                                // trecho que não deu para ler: zeros (o .tar continua válido) e o aviso
                                Array.Clear(buffer.Dados, buffer.Tamanho, n);
                                lido = 0;
                                if (!trechoPerdido)
                                {
                                    FicouDeFora(naoLidos, e.Caminho, "trecho não lido (" + Motivo(falha) + "); completado com zeros");
                                    trechoPerdido = true;
                                }
                            }
                            if (lido < n)
                            {
                                // diminuiu durante a leitura: completa com zeros (o tamanho já está no cabeçalho)
                                Array.Clear(buffer.Dados, buffer.Tamanho + lido, n - lido);
                                alterado = true;
                            }
                            sha.TransformBlock(buffer.Dados, buffer.Tamanho, n, null, 0);
                            buffer.Tamanho += n;
                            offset += n;
                            Interlocked.Add(ref lidoBytes, n);
                            if (buffer.Tamanho >= bloco && offset < tamanho)
                            {
                                Dictionary<string, object> meio = new Dictionary<string, object>();
                                meio["caminho"] = e.Caminho;
                                meio["offset"] = offset;
                                meio["tamanho"] = tamanho;
                                meio["modificado"] = modificado;
                                meio["nativo"] = true;
                                Produzir(buffer, meio, ref arquivos, ref naoLidos);
                            }
                        }
                        sha.TransformFinalBlock(new byte[0], 0, 0);
                        if (fs.Length != tamanho) alterado = true;
                        buffer.Zeros(Tar.Preenchimento(tamanho));
                        AdicionarArquivo(arquivos, e.Caminho, tamanho, modificado, Hex(sha.Hash), alterado);
                        Interlocked.Increment(ref lidoArquivos);
                        if (buffer.Tamanho >= bloco) Produzir(buffer, ProximoCursor(indice + 1), ref arquivos, ref naoLidos);
                    }
                }
            }
            if (buffer.Tamanho > 0 || arquivos.Count > 0 || naoLidos.Count > 0)
            {
                Dictionary<string, object> fim = new Dictionary<string, object>();
                fim["fimDaLista"] = true;
                Produzir(buffer, fim, ref arquivos, ref naoLidos);
            }
        }

        void AdicionarArquivo(List<object> arquivos, string caminho, long tamanho, long modificado, string sha, bool alterado)
        {
            Dictionary<string, object> a = new Dictionary<string, object>();
            a["caminho"] = caminho;
            a["tipo"] = "arquivo";
            a["tamanho"] = tamanho;
            a["modificado"] = modificado;
            a["sha256"] = sha;
            if (alterado)
            {
                a["alterado"] = true;
                Interlocked.Increment(ref alterados);
            }
            arquivos.Add(a);
        }

        // ------------------------------------------------------------ compactação e cifra (em paralelo)

        void Compactar()
        {
            foreach (Bloco b in fila.GetConsumingEnumerable())
            {
                if (cancelado || fatal != null) return;
                int tamanho;
                byte[] quadro = Zstd.Compactar(b.Bruto, Nivel, out tamanho);
                byte[] cifrado = cifra.Cifrar(quadro, tamanho, b.Seq);
                string shaQuadro;
                using (SHA256 sha = new SHA256Cng()) shaQuadro = Hex(sha.ComputeHash(cifrado));
                Dictionary<string, object> cab = new Dictionary<string, object>();
                cab["seq"] = b.Seq;
                cab["geracao"] = geracao;
                cab["cifrado"] = true;
                cab["shaQuadro"] = shaQuadro;
                cab["bruto"] = b.Bruto.Length;
                cab["fim"] = b.Fim;
                cab["arquivos"] = b.Arquivos;
                cab["naoLidos"] = b.NaoLidos;
                byte[] json = Encoding.UTF8.GetBytes(Api.Json.Serialize(cab));
                byte[] envelope = new byte[8 + json.Length + cifrado.Length];
                envelope[0] = (byte)'R'; envelope[1] = (byte)'B'; envelope[2] = (byte)'K'; envelope[3] = (byte)'1';
                envelope[4] = (byte)(json.Length >> 24); envelope[5] = (byte)(json.Length >> 16);
                envelope[6] = (byte)(json.Length >> 8); envelope[7] = (byte)json.Length;
                Buffer.BlockCopy(json, 0, envelope, 8, json.Length);
                Buffer.BlockCopy(cifrado, 0, envelope, 8 + json.Length, cifrado.Length);
                lock (trava)
                {
                    prontos[b.Seq] = envelope;
                    compactadoBytes += cifrado.Length;
                    Monitor.PulseAll(trava);
                }
            }
        }

        // ------------------------------------------------------------ envio (em paralelo, na ordem)

        void Enviar()
        {
            while (true)
            {
                long seq;
                byte[] envelope;
                lock (trava)
                {
                    while (true)
                    {
                        if (fatal != null || cancelado) return;
                        if (proximoAEnviar < ack) proximoAEnviar = ack;
                        if (producaoTerminou && proximoAEnviar >= produzidos) return;
                        if (prontos.ContainsKey(proximoAEnviar)) break;
                        Monitor.Wait(trava, 1000);
                    }
                    seq = proximoAEnviar++;
                    envelope = prontos[seq];
                }
                EnviarUm(seq, envelope);
            }
        }

        void EnviarUm(long seq, byte[] envelope)
        {
            int pausa = 1000;
            while (fatal == null && !cancelado)
            {
                lock (trava) { if (seq < ack) return; }
                try
                {
                    Resposta r = api.Pedir("PUT", "partes/" + seq + "?geracao=" + geracao, envelope, "application/octet-stream",
                        120000 + envelope.Length / 10);
                    if (r.Status == 200 && r.Json != null)
                    {
                        long proximo = Long(Pegar(r.Json, "proximoSeq"));
                        lock (trava)
                        {
                            while (ack < proximo)
                            {
                                if (prontos.Remove(ack)) vagas.Release();
                                ack++;
                            }
                            enviadoBytes = Math.Max(enviadoBytes, Long(Pegar(r.Json, "bytesRecebidos")));
                            amostras.Enqueue(new long[] { DateTime.UtcNow.Ticks, enviadoBytes });
                            while (amostras.Count > 2 && DateTime.UtcNow.Ticks - amostras.Peek()[0] > TimeSpan.FromSeconds(30).Ticks) amostras.Dequeue();
                            aviso = "";
                            Monitor.PulseAll(trava);
                        }
                        return;
                    }
                    string codigo = r.Codigo;
                    if (codigo != null && Fatais.Contains(codigo))
                    {
                        fatal = r.Mensagem ?? codigo;
                        lock (trava) Monitor.PulseAll(trava);
                        return;
                    }
                    if (codigo == "RECEPTOR_AUSENTE")
                    {
                        aviso = r.Mensagem;
                        Thread.Sleep(5000);
                        continue;
                    }
                    if (codigo == "AGUARDE" || codigo == "PEDACO_ADIANTADO")
                    {
                        Thread.Sleep(300);
                        continue;
                    }
                    aviso = "O sistema respondeu " + r.Status + (r.Mensagem != null ? ": " + r.Mensagem : "") + "; mandando de novo.";
                }
                catch (Exception e)
                {
                    aviso = "Sem conexão com o sistema (" + e.Message + "); tentando de novo em " + (pausa / 1000) + " s.";
                }
                Thread.Sleep(pausa);
                pausa = Math.Min(pausa * 2, 30000);
            }
        }

        // ------------------------------------------------------------ andamento e conclusão

        static string Formatar(double n)
        {
            string[] u = { "B", "KB", "MB", "GB", "TB" };
            int i = 0;
            while (n >= 1024 && i < u.Length - 1) { n /= 1024; i++; }
            return i == 0 ? n + " B" : n.ToString("N1") + " " + u[i];
        }

        static string Duracao(double s)
        {
            if (double.IsNaN(s) || double.IsInfinity(s) || s < 0) return "—";
            int t = (int)Math.Round(s);
            if (t < 60) return t + " s";
            if (t < 3600) return (t / 60) + " min " + (t % 60).ToString("00") + " s";
            return (t / 3600) + " h " + (t / 60 % 60).ToString("00") + " min";
        }

        double Velocidade()
        {
            lock (trava)
            {
                if (amostras.Count < 2) return 0;
                long[] a = amostras.Peek();
                long[] b = null;
                foreach (long[] x in amostras) b = x;
                double s = (DateTime.UtcNow.Ticks - a[0]) / (double)TimeSpan.TicksPerSecond;
                return s <= 0 ? 0 : (b[1] - a[1]) / s;
            }
        }

        void Desenhar()
        {
            double pct = totalBytes > 0 ? Math.Min(100, Interlocked.Read(ref lidoBytes) * 100.0 / totalBytes) : 100;
            double comp;
            long enviado;
            bool terminou;
            lock (trava) { comp = compactadoBytes; enviado = enviadoBytes; terminou = producaoTerminou; }
            double estimativa = terminou ? comp : (brutoProduzido > 0 ? comp * totalBytes / Math.Max(1, brutoProduzido) : 0);
            double vel = Velocidade();
            string previsao = vel > 0 && estimativa > enviado ? Duracao((estimativa - enviado) / vel) : "—";
            string linha = string.Format("  Lido {0,5:0.0}%  ({1} de {2})  ·  enviado {3}{4}  ·  {5}/s  ·  falta {6}",
                pct, Formatar(Interlocked.Read(ref lidoBytes)), Formatar(totalBytes), Formatar(enviado),
                estimativa > 0 ? " de ~" + Formatar(estimativa) : "", Formatar(vel), previsao);
            int largura = 119;
            try { largura = Math.Max(40, Console.WindowWidth - 1); } catch { }
            if (linha.Length > largura) linha = linha.Substring(0, largura);
            Console.Write("\r" + linha.PadRight(largura));
            string a = aviso;
            if (!string.IsNullOrEmpty(a) && a != ultimoAviso)
            {
                Console.WriteLine();
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("  " + a);
                Console.ResetColor();
            }
            ultimoAviso = a;
        }

        string ultimoAviso = "";

        void Progresso()
        {
            try
            {
                Dictionary<string, object> p = new Dictionary<string, object>();
                bool terminou;
                lock (trava) terminou = producaoTerminou;
                p["fase"] = terminou ? "enviando" : "lendo";
                p["lidoBytes"] = Interlocked.Read(ref lidoBytes);
                p["lidoArquivos"] = Interlocked.Read(ref lidoArquivos);
                p["compactadoBytes"] = compactadoBytes;
                p["bufferBytes"] = 0;
                p["arquivoAtual"] = arquivoAtual;
                api.Json_("POST", "progresso", p, 20000);
            }
            catch { }
        }

        int Concluir()
        {
            Dictionary<string, object> pedido = new Dictionary<string, object>();
            pedido["geracao"] = geracao;
            pedido["totalPartes"] = produzidos;
            int pausa = 1000;
            while (!cancelado)
            {
                try
                {
                    Resposta r = api.Json_("POST", "concluir", pedido, 120000);
                    if (r.Status == 200) break;
                    if (r.Status < 500 && r.Status != 408 && r.Status != 429)
                    {
                        Console.ForegroundColor = ConsoleColor.Red;
                        Console.WriteLine("  O sistema não aceitou a conclusão: " + (r.Mensagem ?? r.Status.ToString()));
                        Console.ResetColor();
                        return 1;
                    }
                }
                catch (Exception e)
                {
                    Console.WriteLine("  Sem conexão para concluir (" + e.Message + "); tentando de novo…");
                }
                Thread.Sleep(pausa);
                pausa = Math.Min(pausa * 2, 30000);
            }
            TimeSpan duracao = DateTime.UtcNow - inicio;
            Console.ForegroundColor = ConsoleColor.Green;
            Console.WriteLine("  Tudo enviado em " + Duracao(duracao.TotalSeconds) + ": " + Formatar(totalBytes) + " lidos, "
                + Formatar(compactadoBytes) + " pela internet.");
            Console.ResetColor();
            if (alterados > 0) Console.WriteLine("  " + alterados + " arquivo(s) mudaram durante a leitura (estão marcados no relatório).");
            if (deFora.Count > 0)
            {
                Console.ForegroundColor = ConsoleColor.Yellow;
                Console.WriteLine("  Ficaram de fora (" + deFora.Count + "; a lista inteira vai no relatório):");
                for (int i = 0; i < Math.Min(10, deFora.Count); i++) Console.WriteLine("    " + deFora[i]);
                if (deFora.Count > 10) Console.WriteLine("    … e mais " + (deFora.Count - 10));
                Console.ResetColor();
            }
            Console.WriteLine("  Esperando o computador que recebe conferir cada arquivo (pode fechar esta janela se quiser)…");
            DateTime limite = DateTime.UtcNow.AddMinutes(30);
            while (!cancelado && DateTime.UtcNow < limite)
            {
                try
                {
                    Resposta r = api.Pedir("GET", "coleta", null, null, 30000);
                    string situacao = r.Json == null ? null : Pegar(r.Json, "situacao") as string;
                    object conf = r.Json == null ? null : Pegar(r.Json, "conferencia");
                    string mensagem = conf is Dictionary<string, object> ? Pegar((Dictionary<string, object>)conf, "mensagem") as string : null;
                    if (situacao == "INTEGRA")
                    {
                        Console.ForegroundColor = ConsoleColor.Green;
                        Console.WriteLine("  Backup recebido inteiro e conferido. " + (mensagem ?? ""));
                        Console.ResetColor();
                        return 0;
                    }
                    if (situacao == "DIVERGENTE")
                    {
                        Console.ForegroundColor = ConsoleColor.Red;
                        Console.WriteLine("  O backup chegou, mas a conferência encontrou diferenças: " + (mensagem ?? ""));
                        Console.ResetColor();
                        return 1;
                    }
                    if (situacao != "FECHANDO") return 0;
                }
                catch { }
                Thread.Sleep(3000);
            }
            return 0;
        }
    }
}
'@
}

$versaoZstd = [PlumbColetor.Nativo]::Carregar($dll)

# ---------------------------------------------------------------- a sessão (continuar de onde parou com o mesmo comando)
$pastaSessoes = Join-Path $PastaDoColetor 'sessoes'
New-Item -ItemType Directory -Force -Path $pastaSessoes | Out-Null
$sha = [Security.Cryptography.SHA256]::Create()
$idSessao = ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::ASCII.GetBytes($Token))) -replace '-', '').Substring(0, 24).ToLowerInvariant()
$arquivoSessao = Join-Path $pastaSessoes "$idSessao.txt"
if (Test-Path -LiteralPath $arquivoSessao) {
    $Sessao = (Get-Content -LiteralPath $arquivoSessao -Raw).Trim()
} else {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $Sessao = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    Set-Content -LiteralPath $arquivoSessao -Value $Sessao -Encoding ASCII
}

$api = New-Object PlumbColetor.Api $Base, $Token, $Sessao
try {
    $info = $api.InfoTexto() | ConvertFrom-Json
} catch {
    Falha "Não consegui falar com o sistema em $Base ($($_.Exception.Message)). Confira a internet e se o link ainda vale."
}
Info "Cliente: $($info.cliente)"
if ($info.situacao -notin @('AGUARDANDO', 'RECEBENDO')) {
    Falha "Esta coleta não aceita mais envios (situação: $($info.situacao)). Gere um link novo na tela Backups de clientes."
}
if ($info.emOutroComputador) {
    Falha ("Esta coleta já começou em outro computador ou pelo navegador. Para continuar por aqui, peça a " +
        "$(if ($info.recebidoPor) { $info.recebidoPor } else { 'quem gerou o link' }) para liberar na tela Backups de clientes e rode de novo.")
}
if (-not $info.receptorOnline) {
    Aviso "O computador que recebe ($($info.recebidoPor)) está com a tela Backups de clientes fechada: o envio espera ela abrir."
}

# ---------------------------------------------------------------- a pasta
if (-not $Pasta) {
    $sugestao = if ($info.raiz -and $info.proximoSeq -gt 0 -and $info.pastaSistema) { $info.pastaSistema } elseif ($info.pastaSistema) { $info.pastaSistema } else { $null }
    Write-Host ''
    if ($sugestao -and (Test-Path -LiteralPath $sugestao -PathType Container)) {
        $resposta = Read-Host "  Pasta do sistema a copiar [Enter = $sugestao]"
        $Pasta = if ($resposta.Trim()) { $resposta.Trim().Trim('"') } else { $sugestao }
    } else {
        $Pasta = (Read-Host '  Pasta do sistema a copiar (ex.: C:\sislojas)').Trim().Trim('"')
    }
}
if (-not $Pasta -or -not (Test-Path -LiteralPath $Pasta -PathType Container)) { Falha "Pasta não encontrada: $Pasta" }
$Pasta = (Resolve-Path -LiteralPath $Pasta).ProviderPath.TrimEnd('\')
$nomeDaRaiz = Split-Path $Pasta -Leaf
if (-not $nomeDaRaiz -or $Pasta -match '^[A-Za-z]:$') { Falha 'Escolha a pasta do sistema, não a raiz do disco.' }
if ($info.proximoSeq -gt 0 -and $info.raiz -and $info.raiz -ne $nomeDaRaiz) {
    Falha "Esta coleta começou na pasta ""$($info.raiz)"". Para continuar, use a mesma pasta (ou gere um link novo)."
}

# ---------------------------------------------------------------- cópia de sombra (VSS)
$admin = (New-Object Security.Principal.WindowsPrincipal ([Security.Principal.WindowsIdentity]::GetCurrent())).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
$marcaDaSombra = Join-Path $PastaDoColetor 'sombra-em-uso.txt'

function Remover-Sombra([string] $id, [string] $atalho) {
    if ($atalho -and (Test-Path -LiteralPath $atalho)) { cmd /c rmdir "$atalho" | Out-Null }
    if ($id) {
        Get-CimInstance -ClassName Win32_ShadowCopy -Filter "ID='$id'" -ErrorAction SilentlyContinue |
            Remove-CimInstance -ErrorAction SilentlyContinue
    }
}

# uma cópia de sombra esquecida (a janela foi fechada no meio da última vez)
if ($admin -and (Test-Path -LiteralPath $marcaDaSombra)) {
    $antiga = (Get-Content -LiteralPath $marcaDaSombra -Raw).Trim() -split '\|'
    Remover-Sombra $antiga[0] $antiga[1]
    Remove-Item -LiteralPath $marcaDaSombra -Force -ErrorAction SilentlyContinue
}

$sombraId = $null
$atalhoSombra = $null
$leitura = $Pasta
$volume = [IO.Path]::GetPathRoot($Pasta)
if ($SemSombra) {
    Aviso 'Sem cópia de sombra (-SemSombra): arquivos em uso pelo sistema podem ficar de fora.'
} elseif (-not $admin) {
    Aviso 'O PowerShell não está como administrador: sem a cópia de sombra, arquivos em uso pelo sistema (.cfg, .dat'
    Aviso 'abertos) podem ficar de fora. O melhor é fechar e abrir o PowerShell com "Executar como administrador".'
    $continuar = Read-Host '  Continuar assim mesmo? (s/N)'
    if ($continuar -notmatch '^[sS]') { $codigo = 2; Parar }
} elseif ($volume -notmatch '^[A-Za-z]:\\$') {
    Aviso "A pasta está na rede ($volume): sem cópia de sombra. O ideal é rodar o coletor no computador onde a pasta fica."
} else {
    Titulo 'Cópia de sombra'
    Info "Tirando uma foto do disco $volume (arquivos em uso vêm inteiros)…"
    try {
        $r = Invoke-CimMethod -ClassName Win32_ShadowCopy -MethodName Create -Arguments @{ Volume = $volume; Context = 'ClientAccessible' }
        if ($r.ReturnValue -ne 0) { throw "o Windows respondeu $($r.ReturnValue)" }
        $sombraId = $r.ShadowID
        $sombra = Get-CimInstance -ClassName Win32_ShadowCopy -Filter "ID='$sombraId'"
        $atalhoSombra = Join-Path $PastaDoColetor ('sombra-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
        Set-Content -LiteralPath $marcaDaSombra -Value "$sombraId|$atalhoSombra" -Encoding ASCII
        cmd /c mklink /d "$atalhoSombra" "$($sombra.DeviceObject)\" | Out-Null
        $leitura = Join-Path $atalhoSombra $Pasta.Substring($volume.Length)
        if (-not (Test-Path -LiteralPath $leitura -PathType Container)) { throw 'a pasta não apareceu na cópia de sombra' }
        Ok 'Pronta.'
    } catch {
        Remover-Sombra $sombraId $atalhoSombra
        Remove-Item -LiteralPath $marcaDaSombra -Force -ErrorAction SilentlyContinue
        $sombraId = $null; $atalhoSombra = $null; $leitura = $Pasta
        Aviso "Não deu para usar a cópia de sombra ($($_.Exception.Message)): lendo direto da pasta."
        Aviso 'Arquivos em uso pelo sistema podem ficar de fora (o relatório mostra quais).'
    }
}

try {
    # ------------------------------------------------------------ o que levar
    Titulo "Pasta $Pasta"
    Info 'Listando…'
    $listagem = [PlumbColetor.Lista]::Listar($leitura, $nomeDaRaiz)
    $grupos = @{}
    foreach ($e in $listagem.Entradas) {
        if ($null -eq $e.Topo) { continue }
        if (-not $grupos.ContainsKey($e.Topo)) { $grupos[$e.Topo] = [pscustomobject]@{ Topo = $e.Topo; Arquivos = 0; Bytes = [long]0 } }
        if (-not $e.Pasta) { $grupos[$e.Topo].Arquivos++; $grupos[$e.Topo].Bytes += $e.Tamanho }
    }
    $lista = @($grupos.Values | Sort-Object @{ Expression = { $_.Topo -eq '__arquivos-da-raiz__' } }, Topo)
    $i = 0
    foreach ($g in $lista) {
        $i++
        $nome = if ($g.Topo -eq '__arquivos-da-raiz__') { '(arquivos soltos na pasta)' } else { $g.Topo }
        Write-Host ('  {0,3}  {1,-40} {2,10:N0} arquivos {3,12}' -f $i, $nome, $g.Arquivos, (Formatar-Bytes $g.Bytes))
    }
    $totalArquivos = ($lista | Measure-Object Arquivos -Sum).Sum
    $totalBytes = ($lista | Measure-Object Bytes -Sum).Sum
    Info ('Total: {0:N0} arquivos, {1}' -f $totalArquivos, (Formatar-Bytes $totalBytes))
    foreach ($n in $listagem.NaoLidos) { Aviso "De fora: $($n.Caminho) ($($n.Motivo))" }

    $fora = @()
    if (-not $Tudo -and $info.proximoSeq -eq 0 -and $lista.Count -gt 1) {
        $resposta = Read-Host '  Enter = levar tudo. Para deixar pastas de fora, digite os números (ex.: 3 5)'
        foreach ($n in ($resposta -split '[\s,;]+' | Where-Object { $_ -match '^\d+$' })) {
            $k = [int]$n
            if ($k -ge 1 -and $k -le $lista.Count) { $fora += $lista[$k - 1].Topo }
        }
    } elseif ($info.proximoSeq -gt 0 -and $info.selecaoAnterior) {
        # continuando: as mesmas pastas da primeira vez
        $fora = @($lista | Where-Object { $info.selecaoAnterior -notcontains $_.Topo } | ForEach-Object Topo)
    }
    $entradas = New-Object 'System.Collections.Generic.List[PlumbColetor.Entrada]'
    foreach ($e in $listagem.Entradas) { if ($null -eq $e.Topo -or $fora -notcontains $e.Topo) { $entradas.Add($e) } }
    $pastas = New-Object 'System.Collections.Generic.List[string]'
    foreach ($g in $lista) { if ($fora -notcontains $g.Topo) { $pastas.Add($g.Topo) } }
    if ($fora.Count -gt 0) { Aviso "De fora: $($fora -join ', ')" }

    # ------------------------------------------------------------ coleta
    Titulo 'Enviando'
    $nucleos = [Environment]::ProcessorCount
    $coletor = New-Object PlumbColetor.Coletor
    $coletor.Base = $Base
    $coletor.Token = $Token
    $coletor.Sessao = $Sessao
    $coletor.Chave = $Chave
    $coletor.RaizNome = $nomeDaRaiz
    $coletor.Entradas = $entradas
    $coletor.NaoLidosIniciais = $listagem.NaoLidos
    $coletor.Pastas = $pastas
    $coletor.Nivel = $Nivel
    $coletor.Envios = $Envios
    $coletor.Compactadores = [Math]::Max(1, [Math]::Min(8, $nucleos - 1))
    $windows = [Environment]::OSVersion.Version.ToString()
    $coletor.Descricao = "Coletor nativo $VersaoColetor (Windows $windows, $nucleos núcleos, zstd $versaoZstd nível $Nivel, " +
        "cópia de sombra: $(if ($sombraId) { 'sim' } else { 'não' }))"
    Info "zstd $versaoZstd nível $Nivel em $($coletor.Compactadores) núcleo(s), $Envios envio(s) em paralelo. Ctrl+C interrompe (rode de novo para continuar)."
    $codigo = $coletor.Executar()
} finally {
    if ($sombraId -or $atalhoSombra) {
        Remover-Sombra $sombraId $atalhoSombra
        Remove-Item -LiteralPath $marcaDaSombra -Force -ErrorAction SilentlyContinue
    }
}
Write-Host ''
if ($codigo -eq 0) { Remove-Item -LiteralPath $arquivoSessao -Force -ErrorAction SilentlyContinue }

} catch {
    if ("$($_.Exception.Message)" -ne 'PLUMB_PAROU') {
        Write-Host ''
        Write-Host "  O coletor parou por um erro: $($_.Exception.Message)" -ForegroundColor Red
        Write-Host '  Rode o mesmo comando de novo para continuar de onde parou.' -ForegroundColor Red
        Write-Host ''
    }
    if ($codigo -eq 0) { $codigo = 1 }
}

# o comando colado leva a chave do link: tira ele do histórico do PowerShell deste computador
try {
    $historico = (Get-PSReadLineOption -ErrorAction Stop).HistorySavePath
    if ($historico -and (Test-Path -LiteralPath $historico)) {
        $linhas = [IO.File]::ReadAllLines($historico)
        $limpas = @($linhas | Where-Object { -not $_.Contains($m.Groups[3].Value) })
        if ($limpas.Count -ne $linhas.Count) { [IO.File]::WriteAllLines($historico, [string[]] $limpas) }
    }
} catch { }
$global:LASTEXITCODE = $codigo
