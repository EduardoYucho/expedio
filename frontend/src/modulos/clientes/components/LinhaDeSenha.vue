<script setup>
/**
 * Uma senha do cofre numa ficha: escondida (••••), com mostrar / copiar / apagar para quem usa as senhas. O valor só
 * chega aqui (em {@code visivel}) depois que o cofre o entregou, e some sozinho.
 */
defineProps({
  rotulo: { type: String, default: 'Senha' },
  visivel: { type: String, default: null },
  podeUsar: { type: Boolean, default: false },
  alteradaEm: { type: String, default: null },
  alteradaPor: { type: String, default: null },
})
defineEmits(['mostrar', 'copiar', 'apagar'])

const dataHora = (v) => (v ? new Date(v).toLocaleString('pt-BR', { dateStyle: 'short', timeStyle: 'short' }) : '—')
</script>

<template>
  <dt class="rotulo self-center">{{ rotulo }}</dt>
  <dd class="flex flex-wrap items-center gap-2">
    <span class="carimbo">{{ visivel ?? '••••••••' }}</span>
    <template v-if="podeUsar">
      <button type="button" class="text-xs underline underline-offset-4" @click="$emit('mostrar')">{{ visivel ? 'esconder' : 'mostrar' }}</button>
      <button type="button" class="text-xs underline underline-offset-4" @click="$emit('copiar')">copiar</button>
      <button type="button" class="text-xs text-carimbo underline underline-offset-4" @click="$emit('apagar')">apagar senha</button>
    </template>
    <span class="text-xs text-tinta-apagada">alterada {{ dataHora(alteradaEm) }} por {{ alteradaPor }}</span>
  </dd>
</template>
