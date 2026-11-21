/**
 * Módulo <b>Clientes</b>: a ficha de cada cliente (grupo, situação, alertas, contatos, máquinas, acessos remotos e
 * anotações) e o <b>cofre</b> das senhas de acesso.
 *
 * <ul>
 *   <li>As senhas ficam cifradas (AES-256-GCM) com a chave do cofre, um arquivo fora do banco; só saem por uma rota,
 *       com o cofre aberto pelo código do app autenticador (TOTP) e registro de quem viu ou copiou.</li>
 *   <li>O backup leva só a chave embrulhada pela frase de recuperação, nunca a chave crua.</li>
 *   <li>Como os outros módulos, não conhece os demais módulos e usa do Expedio só o permitido
 *       ({@code ArquiteturaModulosTest}).</li>
 * </ul>
 */
package br.com.expedio.modulos.clientes;
