<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import QRCode from 'qrcode'
import { mensagemDe } from '@/utils/erros'
import { cofreApi } from '../api'
import { useCofreStore } from '../cofre'

/**
 * O código do celular. Na primeira vez, mostra o QR code para o app autenticador (Google ou Microsoft Authenticator)
 * e confirma com o primeiro código; depois, só pede o código de 6 dígitos para abrir o cofre por alguns minutos.
 */
const cofre = useCofreStore()
const codigo = ref('')
const erro = ref('')
const ocupado = ref(false)
const novo = ref(null)
const qr = ref('')
const campo = ref(null)

const estado = computed(() => cofre.estado)
const ativando = computed(() => estado.value && !estado.value.autenticadorAtivo)

watch(() => cofre.pedindo, async (pedindo) => {
  if (!pedindo) return
  codigo.value = ''
  erro.value = ''
  novo.value = null
  qr.value = ''
  try {
    await cofre.carregar()
  } catch (e) {
    erro.value = mensagemDe(e)
  }
  await nextTick()
  campo.value?.focus()
})

async function gerarQr() {
  erro.value = ''
  ocupado.value = true
  try {
    novo.value = await cofreApi.novoAutenticador()
    qr.value = await QRCode.toDataURL(novo.value.endereco, { margin: 1, width: 220, errorCorrectionLevel: 'M' })
    await nextTick()
    campo.value?.focus()
  } catch (e) {
    erro.value = mensagemDe(e)
  } finally {
    ocupado.value = false
  }
}

async function confirmar() {
  const digitado = codigo.value.replace(/\D/g, '')
  if (digitado.length !== 6) {
    erro.value = 'Digite os 6 números que aparecem no app.'
    return
  }
  erro.value = ''
  ocupado.value = true
  try {
    if (ativando.value) await cofre.ativar(digitado)
    else await cofre.abrir(digitado)
    novo.value = null
    qr.value = ''
  } catch (e) {
    erro.value = mensagemDe(e)
    codigo.value = ''
    campo.value?.focus()
  } finally {
    ocupado.value = false
  }
}
</script>

<template>
  <div
    v-if="cofre.pedindo"
    class="fixed inset-0 z-50 grid place-items-center bg-tinta/40 px-4"
    role="dialog"
    aria-modal="true"
    aria-labelledby="titulo-cofre"
    @keydown.esc="cofre.cancelar()"
  >
    <form class="cartao w-full max-w-md px-6 py-5" @submit.prevent="confirmar">
      <p class="rotulo text-carimbo">Cofre dos clientes</p>
      <h2 id="titulo-cofre" class="mt-1 text-xl font-extrabold tracking-tight">
        {{ ativando ? 'Ative o código do celular' : 'Código do celular' }}
      </h2>

      <p v-if="estado?.chave === 'TRANCADA'" class="mt-3 rounded-[3px] border border-carimbo/40 bg-carimbo/5 px-3 py-2 text-sm text-carimbo">
        O cofre está trancado: a chave dele não está neste computador. Quem administra o cofre restaura com a frase de
        recuperação (Clientes › Cofre).
      </p>

      <template v-else-if="ativando">
        <p class="mt-2 text-sm text-tinta-suave">
          As senhas dos clientes só abrem com um código que muda a cada 30 segundos. Instale no celular o
          <b>Google Authenticator</b> ou o <b>Microsoft Authenticator</b> e leia o QR code.
        </p>
        <div v-if="!novo" class="mt-4">
          <button type="button" class="botao-primario" :disabled="ocupado" @click="gerarQr">
            {{ ocupado ? 'Gerando…' : 'Mostrar o QR code' }}
          </button>
        </div>
        <div v-else class="mt-4 flex flex-col items-center gap-3">
          <img :src="qr" alt="QR code para o app autenticador" class="size-[220px] rounded-[3px] border border-linha bg-white" />
          <p class="text-center text-xs text-tinta-suave">
            Sem câmera? No app, escolha "inserir chave" e digite:<br />
            <span class="carimbo text-sm text-tinta select-all">{{ novo.segredo }}</span>
          </p>
        </div>
      </template>
      <p v-else class="mt-2 text-sm text-tinta-suave">
        Abra o app autenticador e digite o código do <b>Plumb</b>. O cofre fica aberto por alguns minutos nesta aba.
      </p>

      <label v-if="estado?.chave !== 'TRANCADA' && (!ativando || novo)" class="mt-4 block">
        <span class="rotulo">Código de 6 dígitos</span>
        <input
          ref="campo"
          v-model="codigo"
          class="campo mt-1 text-center text-2xl tracking-[0.4em]"
          inputmode="numeric"
          autocomplete="one-time-code"
          maxlength="7"
          placeholder="000000"
          aria-describedby="erro-cofre"
        />
      </label>

      <p v-if="erro" id="erro-cofre" class="mt-3 text-sm font-semibold text-carimbo" role="alert">{{ erro }}</p>

      <div class="mt-5 flex justify-end gap-2">
        <button type="button" class="botao-secundario" @click="cofre.cancelar()">Cancelar</button>
        <button
          v-if="estado?.chave !== 'TRANCADA' && (!ativando || novo)"
          type="submit"
          class="botao-primario"
          :disabled="ocupado"
        >
          {{ ocupado ? 'Conferindo…' : ativando ? 'Ativar e abrir' : 'Abrir o cofre' }}
        </button>
      </div>
    </form>
  </div>
</template>
