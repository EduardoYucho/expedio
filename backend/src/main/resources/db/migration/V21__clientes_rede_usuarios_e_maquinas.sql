-- Clientes: a rede dos acessos (Radmin, VPN), os usuários e senhas do cliente e os dados das máquinas.

-- -------------------------------------------------------------- acessos: a rede para entrar (Radmin VPN, VPN, ZeroTier…)
-- Além do usuário e da senha da máquina (Windows/RDP), o acesso pode ter o nome da rede e a senha dela (cifrada no
-- cofre, com dono próprio: não troca de lugar com a senha da máquina).
ALTER TABLE clientes.acesso
    ADD COLUMN rede                     VARCHAR(120),
    ADD COLUMN rede_senha_cifrada       BYTEA,
    ADD COLUMN rede_senha_vetor         BYTEA,
    ADD COLUMN rede_senha_alterada_em   TIMESTAMPTZ,
    ADD COLUMN rede_senha_alterada_por  VARCHAR(60),
    ADD CONSTRAINT ck_cli_acesso_rede_senha CHECK ((rede_senha_cifrada IS NULL) = (rede_senha_vetor IS NULL));

-- -------------------------------------------------------------- usuários e senhas do cliente
-- Sistema da loja (os usuários de cada funcionário), Windows, banco de dados e outros serviços (e-mail, certificado,
-- roteador, portal da SEFAZ…). A senha fica no cofre, como a dos acessos.
CREATE TABLE clientes.credencial (
    id                  UUID          NOT NULL,
    cliente_id          UUID          NOT NULL,
    maquina_id          UUID,
    onde                VARCHAR(20)   NOT NULL,
    servico             VARCHAR(120),
    usuario             VARCHAR(160)  NOT NULL,
    observacao          VARCHAR(500),
    senha_cifrada       BYTEA,
    senha_vetor         BYTEA,
    senha_alterada_em   TIMESTAMPTZ,
    senha_alterada_por  VARCHAR(60),
    criado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por      VARCHAR(60)   NOT NULL,
    CONSTRAINT pk_cli_credencial PRIMARY KEY (id),
    CONSTRAINT fk_cli_credencial_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE,
    CONSTRAINT fk_cli_credencial_maquina FOREIGN KEY (maquina_id) REFERENCES clientes.maquina (id) ON DELETE SET NULL,
    CONSTRAINT ck_cli_credencial_onde CHECK (onde IN ('SISTEMA', 'WINDOWS', 'BANCO', 'OUTRO')),
    CONSTRAINT ck_cli_credencial_senha CHECK ((senha_cifrada IS NULL) = (senha_vetor IS NULL))
);
CREATE INDEX ix_cli_credencial_cliente ON clientes.credencial (cliente_id);

ALTER TABLE clientes.uso_de_senha ADD COLUMN credencial_id UUID;

-- -------------------------------------------------------------- máquinas: rede, sistema e hardware
ALTER TABLE clientes.maquina
    ADD COLUMN nome_computador      VARCHAR(80),
    ADD COLUMN ip_local             VARCHAR(60),
    ADD COLUMN ip_radmin            VARCHAR(60),
    ADD COLUMN sistema_operacional  VARCHAR(120),
    ADD COLUMN banco_dados          VARCHAR(120),
    ADD COLUMN local_base           VARCHAR(260),
    ADD COLUMN processador          VARCHAR(120),
    ADD COLUMN memoria              VARCHAR(40),
    ADD COLUMN disco                VARCHAR(120),
    ADD COLUMN local_backup         VARCHAR(260),
    ALTER COLUMN observacao TYPE VARCHAR(4000);
