# Variáveis de ambiente do back-end para rodar pelo terminal (iniciar.ps1).
#
# Na primeira execução, o iniciar.ps1 copia este arquivo para ambiente.local.ps1 (fora do
# controle de versão) e gera o segredo JWT. Edite o ambiente.local.ps1, não este.

# Assina os tokens de login. Mantenha fixo: se mudar, todos precisam entrar de novo.
# Dica: use o mesmo valor no Eclipse (Run Configurations > Environment).
$env:PLUMB_JWT_SEGREDO = '<gerado-na-primeira-execucao>'

# Senhas iniciais: só valem quando o usuário ainda NÃO existe no banco.
# Em branco, a senha é gerada e aparece uma única vez no log.
# $env:PLUMB_ADMIN_SENHA  = ''
# $env:PLUMB_VIEWER_SENHA = ''

# Pasta dos comprovantes: cada usuário escolhe a sua em "Minha conta". Esta variável só vale na primeira
# subida da versão com vários usuários (vira a pasta do administrador, se ninguém tiver pasta).
# $env:PLUMB_PDF_DIR = "$HOME\Downloads\Ponto"

# Onde os PDFs ficam arquivados (padrão: .plumb\comprovantes na pasta do usuário)
# $env:PLUMB_ARMAZENAMENTO_DIR = "$HOME\.plumb\comprovantes"

# Logs (padrão: .plumb\logs na pasta do usuário): plumb.log com tudo e, em
# usuarios\<login>\<aaaa-mm-dd>\<hh>h.log, um arquivo por usuário e por hora (guardados por 30 dias)
# $env:PLUMB_LOGS_DIR = "$HOME\.plumb\logs"

# Banco (padrão: localhost:5432/plumb, usuário e senha "plumb")
# $env:DB_URL      = 'jdbc:postgresql://localhost:5432/plumb'
# $env:DB_USER     = 'plumb'
# $env:DB_PASSWORD = 'plumb'

# Porta HTTP (padrão 8080; o front-end em desenvolvimento aponta para a 8080)
# $env:PORT = '8080'
