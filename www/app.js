// ===========================================================
// CamSegurança — app self-service (Capacitor)
// Pareamento único do celular dedicado + acionamento do serviço
// nativo Android (foreground service) que grava e envia os
// segmentos de vídeo/áudio continuamente.
// ===========================================================

const API_BASE = 'https://www.kallahari.com.br/camseguranca/api/';
const LS_PAREADO = 'cs_pareado';

function lerPareamento() {
  try { return JSON.parse(localStorage.getItem(LS_PAREADO)); } catch { return null; }
}
function salvarPareamento(o) { localStorage.setItem(LS_PAREADO, JSON.stringify(o)); }
function limparPareamento() { localStorage.removeItem(LS_PAREADO); }

function getPlugin() {
  return (window.Capacitor && window.Capacitor.Plugins && window.Capacitor.Plugins.CameraServico) || null;
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}

function init() {
  const pareado = lerPareamento();
  if (pareado && pareado.token) {
    mostrarTelaAtiva(pareado);
  } else {
    mostrarTelaPareamento();
  }
}

function mostrarTelaPareamento() {
  document.getElementById('tela-pareamento').classList.remove('oculto');
  document.getElementById('tela-ativa').classList.add('oculto');
  document.getElementById('btn-parear').addEventListener('click', parear);
}

async function parear() {
  const codigo = document.getElementById('input-codigo').value.trim().toUpperCase();
  const btn = document.getElementById('btn-parear');
  const erroEl = document.getElementById('erro-pareamento');
  erroEl.classList.add('oculto');

  if (codigo.length < 4) {
    erroEl.textContent = 'Digite o código completo.';
    erroEl.classList.remove('oculto');
    return;
  }

  btn.disabled = true;
  btn.textContent = 'Pareando...';
  try {
    const r = await fetch(API_BASE + 'pareamento.php', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ codigo }),
    });
    const data = await r.json();
    if (!data.ok) throw new Error(data.erro || 'Falha no pareamento');

    const pareado = { cameraId: data.camera_id, nome: data.nome, token: data.token };
    salvarPareamento(pareado);
    await iniciarServicoNativo(pareado);
    mostrarTelaAtiva(pareado);
  } catch (e) {
    erroEl.textContent = e.message || 'Erro ao parear. Verifique a conexão.';
    erroEl.classList.remove('oculto');
  } finally {
    btn.disabled = false;
    btn.textContent = 'Parear câmera';
  }
}

async function iniciarServicoNativo(pareado) {
  const plugin = getPlugin();
  if (!plugin) return;
  try {
    await plugin.iniciar({ apiBase: API_BASE, token: pareado.token, cameraNome: pareado.nome });
  } catch (e) {
    console.error('Falha ao iniciar serviço nativo', e);
  }
}

function mostrarTelaAtiva(pareado) {
  document.getElementById('tela-pareamento').classList.add('oculto');
  document.getElementById('tela-ativa').classList.remove('oculto');
  document.getElementById('cam-nome').textContent = pareado.nome;
  document.getElementById('btn-trocar').addEventListener('click', trocarCamera);
  iniciarServicoNativo(pareado);
  atualizarStatusPeriodicamente();
}

async function trocarCamera() {
  if (!confirm('Isso vai parar a gravação atual e pedir um novo código de pareamento. Continuar?')) return;
  const plugin = getPlugin();
  if (plugin) { try { await plugin.parar(); } catch (e) {} }
  limparPareamento();
  location.reload();
}

function atualizarStatusPeriodicamente() {
  const atualizar = async () => {
    const plugin = getPlugin();
    if (!plugin) return;
    try {
      const status = await plugin.status();
      document.getElementById('status-servico').textContent = status.rodando ? 'Gravando' : 'Parado';
      document.getElementById('status-fila').textContent = `${status.filaPendente ?? 0} pendente(s)`;
      document.getElementById('status-sync').textContent = status.ultimaSincronizacao || '—';
    } catch (e) {}
  };
  atualizar();
  setInterval(atualizar, 5000);
}
