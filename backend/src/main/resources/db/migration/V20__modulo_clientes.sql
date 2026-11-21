-- =============================================================================
-- V20: módulo Clientes (schema clientes)
-- -----------------------------------------------------------------------------
-- A ficha de cada cliente e o cofre das senhas de acesso. Nenhuma tabela do
-- Expedio muda; só as permissões novas do módulo entram no perfil Usuário (o
-- Administrador já tem todas). As senhas não entram em nenhum perfil: quem cuida
-- dos acessos libera "Usar as senhas dos clientes" para cada pessoa.
--
-- * grupo, cliente:  a rede de lojas e cada cliente (situação como campo).
-- * alerta:          aviso em destaque na ficha.
-- * contato, maquina, anotacao: o resto da ficha.
-- * acesso:          AnyDesk, Radmin, WTS...: ID/endereço à vista; a senha
--                    só cifrada (AES-256-GCM, a chave fica fora do banco).
-- * autenticador:    o segredo do app autenticador de cada pessoa, cifrado.
-- * uso_de_senha:    quem mostrou, copiou ou alterou cada senha (e as tarefas
--                    do cofre). Sem chave estrangeira: o registro fica mesmo
--                    se o cliente ou o acesso for apagado.
-- =============================================================================

CREATE SCHEMA clientes;
COMMENT ON SCHEMA clientes IS 'Módulo clientes: ficha dos clientes e cofre das senhas de acesso.';

-- Sem acento, para a busca (a extensão unaccent foi criada na V15).
CREATE FUNCTION clientes.sem_acento(texto TEXT) RETURNS TEXT
    LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT
    RETURN public.unaccent('public.unaccent'::regdictionary, texto);

CREATE TABLE clientes.grupo (
    id         UUID          NOT NULL,
    nome       VARCHAR(120)  NOT NULL,
    criado_em  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cli_grupo PRIMARY KEY (id)
);
CREATE UNIQUE INDEX ux_cli_grupo_nome ON clientes.grupo (lower(nome));

CREATE TABLE clientes.cliente (
    id               UUID          NOT NULL,
    nome             VARCHAR(160)  NOT NULL,
    grupo_id         UUID,
    documento        VARCHAR(20),
    cidade           VARCHAR(80),
    ativo            BOOLEAN       NOT NULL DEFAULT TRUE,
    codigo_chamados  VARCHAR(20),
    observacao       TEXT,
    criado_em        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por       VARCHAR(60)   NOT NULL,
    atualizado_em    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por   VARCHAR(60)   NOT NULL,
    CONSTRAINT pk_cli_cliente PRIMARY KEY (id),
    CONSTRAINT fk_cli_cliente_grupo FOREIGN KEY (grupo_id) REFERENCES clientes.grupo (id) ON DELETE SET NULL,
    CONSTRAINT ck_cli_cliente_observacao CHECK (observacao IS NULL OR length(observacao) <= 4000)
);
CREATE INDEX ix_cli_cliente_nome ON clientes.cliente (lower(nome));
CREATE INDEX ix_cli_cliente_grupo ON clientes.cliente (grupo_id);

CREATE TABLE clientes.alerta (
    id          UUID          NOT NULL,
    cliente_id  UUID          NOT NULL,
    texto       VARCHAR(500)  NOT NULL,
    ativo       BOOLEAN       NOT NULL DEFAULT TRUE,
    criado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    criado_por  VARCHAR(60)   NOT NULL,
    CONSTRAINT pk_cli_alerta PRIMARY KEY (id),
    CONSTRAINT fk_cli_alerta_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE
);
CREATE INDEX ix_cli_alerta_cliente ON clientes.alerta (cliente_id);

CREATE TABLE clientes.contato (
    id          UUID          NOT NULL,
    cliente_id  UUID          NOT NULL,
    nome        VARCHAR(120)  NOT NULL,
    funcao      VARCHAR(80),
    telefone    VARCHAR(40),
    email       VARCHAR(160),
    autoriza    BOOLEAN       NOT NULL DEFAULT FALSE,
    observacao  VARCHAR(300),
    criado_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cli_contato PRIMARY KEY (id),
    CONSTRAINT fk_cli_contato_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE
);
CREATE INDEX ix_cli_contato_cliente ON clientes.contato (cliente_id);

CREATE TABLE clientes.maquina (
    id             UUID          NOT NULL,
    cliente_id     UUID          NOT NULL,
    nome           VARCHAR(80)   NOT NULL,
    papel          VARCHAR(20)   NOT NULL,
    pasta_sistema  VARCHAR(260),
    observacao     VARCHAR(500),
    criado_em      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cli_maquina PRIMARY KEY (id),
    CONSTRAINT fk_cli_maquina_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE,
    CONSTRAINT ck_cli_maquina_papel CHECK (papel IN ('SERVIDOR_CENTRAL', 'SERVIDOR', 'CAIXA', 'ESTACAO', 'OUTRA'))
);
CREATE INDEX ix_cli_maquina_cliente ON clientes.maquina (cliente_id);

CREATE TABLE clientes.acesso (
    id                  UUID          NOT NULL,
    cliente_id          UUID          NOT NULL,
    maquina_id          UUID,
    tipo                VARCHAR(20)   NOT NULL,
    identificador       VARCHAR(200)  NOT NULL,
    porta               INTEGER,
    usuario             VARCHAR(120),
    observacao          VARCHAR(500),
    senha_cifrada       BYTEA,
    senha_vetor         BYTEA,
    senha_alterada_em   TIMESTAMPTZ,
    senha_alterada_por  VARCHAR(60),
    criado_em           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_em       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    atualizado_por      VARCHAR(60)   NOT NULL,
    CONSTRAINT pk_cli_acesso PRIMARY KEY (id),
    CONSTRAINT fk_cli_acesso_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE,
    CONSTRAINT fk_cli_acesso_maquina FOREIGN KEY (maquina_id) REFERENCES clientes.maquina (id) ON DELETE SET NULL,
    CONSTRAINT ck_cli_acesso_tipo CHECK (tipo IN ('ANYDESK', 'ULTRAVIEWER', 'RADMIN', 'RDP', 'TEAMVIEWER', 'VPN', 'OUTRO')),
    CONSTRAINT ck_cli_acesso_porta CHECK (porta IS NULL OR porta BETWEEN 1 AND 65535),
    CONSTRAINT ck_cli_acesso_senha CHECK ((senha_cifrada IS NULL) = (senha_vetor IS NULL))
);
CREATE INDEX ix_cli_acesso_cliente ON clientes.acesso (cliente_id);

CREATE TABLE clientes.anotacao (
    id          UUID          NOT NULL,
    cliente_id  UUID          NOT NULL,
    texto       TEXT          NOT NULL,
    autor       VARCHAR(60)   NOT NULL,
    criada_em   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cli_anotacao PRIMARY KEY (id),
    CONSTRAINT fk_cli_anotacao_cliente FOREIGN KEY (cliente_id) REFERENCES clientes.cliente (id) ON DELETE CASCADE,
    CONSTRAINT ck_cli_anotacao_texto CHECK (length(texto) <= 4000)
);
CREATE INDEX ix_cli_anotacao_cliente ON clientes.anotacao (cliente_id, criada_em DESC);

CREATE TABLE clientes.autenticador (
    usuario_id      UUID         NOT NULL,
    segredo_cifrado BYTEA        NOT NULL,
    segredo_vetor   BYTEA        NOT NULL,
    ativado_em      TIMESTAMPTZ,
    ultimo_passo    BIGINT       NOT NULL DEFAULT -1,
    criado_em       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT pk_cli_autenticador PRIMARY KEY (usuario_id),
    CONSTRAINT fk_cli_autenticador_usuario FOREIGN KEY (usuario_id) REFERENCES public.tb_usuario (id) ON DELETE CASCADE
);

CREATE TABLE clientes.uso_de_senha (
    id          BIGSERIAL     NOT NULL,
    em          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    login       VARCHAR(60)   NOT NULL,
    acao        VARCHAR(24)   NOT NULL,
    cliente_id  UUID,
    acesso_id   UUID,
    descricao   VARCHAR(300),
    ip          VARCHAR(45),
    CONSTRAINT pk_cli_uso_de_senha PRIMARY KEY (id)
);
CREATE INDEX ix_cli_uso_cliente ON clientes.uso_de_senha (cliente_id, em DESC);
CREATE INDEX ix_cli_uso_em ON clientes.uso_de_senha (em DESC);

-- -------------------------------------------------------------- permissões
INSERT INTO tb_perfil_permissao (perfil_id, permissao)
SELECT p.id, x.permissao
  FROM tb_perfil_acesso p CROSS JOIN (VALUES ('clientes.ver'), ('clientes.editar')) AS x (permissao)
 WHERE p.sistema = 'ROLE_USER';

INSERT INTO tb_historico_acesso (quem, perfil_id, descricao)
SELECT 'sistema', p.id, 'Perfil ' || p.nome || ': ganhou "Ver os clientes" e "Cadastrar e alterar clientes" (permissões novas)'
  FROM tb_perfil_acesso p WHERE p.sistema = 'ROLE_USER';
