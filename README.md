# CamSegurança — app Android de câmera de segurança fixa

Transforma um celular Android dedicado (fixo, ligado na tomada) em uma câmera de segurança que grava
vídeo + áudio continuamente, envia pro servidor da Kallahari, e permite visualização ao vivo (com ~15-20s
de atraso, padrão de qualquer câmera de segurança por streaming) e consulta de gravações antigas pelo
painel administrativo.

**Transparência é uma decisão de design, não um detalhe**: o indicador de câmera/microfone do Android
aparece normalmente durante toda a gravação, e existe uma notificação persistente ("📷🎙 [nome] —
gravando") que não pode ser escondida — o app não faz nenhuma tentativa de ocultar que está gravando.

## Arquitetura

- **App nativo (Kotlin + Capacitor)**: diferente de um app comum de WebView, aqui a captura de vídeo é
  feita por uma **foreground service** Android nativa (`GravacaoServico.kt`) usando CameraX, porque
  precisa continuar gravando com a tela apagada — algo que APIs de navegador (`getUserMedia`) não
  suportam de forma confiável em segundo plano.
- **Segmentos de ~6s**: a gravação é feita em blocos curtos (não um arquivo único infinito), cada um
  enviado assim que termina. Isso é o que possibilita tanto o "ao vivo" (janela rolante de segmentos no
  servidor) quanto o arquivamento (mesmo arquivo, guardado por mais tempo).
- **Fila de envio offline-first** (`FilaEnvio.kt`): se não tiver internet no momento, o segmento gravado
  fica em uma pasta local até conseguir enviar — nunca perde uma gravação por falta de conexão momentânea
  (limite de 500MB em fila local pra não lotar o armazenamento se ficar muito tempo offline).
- **Sobrevive a reboot**: se o celular desligar/religar (queda de energia), o `BootReceiver.kt` reinicia a
  gravação sozinho, sem precisar abrir o app manualmente.
- **Servidor**: `/var/www/html/camseguranca/` no servidor da Kallahari — recebe os segmentos
  (`api/upload_segmento.php`), remuxa pra `.ts` via `ffmpeg` (sem recodificar) pra alimentar a janela
  `.m3u8` "ao vivo", e arquiva o MP4 original pra consulta histórica em `adm/gravacoes.php`.

## Setup de uma câmera nova

1. No painel (`/camseguranca/adm/cameras.php`), clique em "+ Criar" e dê um nome/local pra câmera. Isso
   gera um **código de pareamento** de 6 caracteres.
2. Instale o APK no celular dedicado (baixe da aba Releases deste repo).
3. Abra o app, digite o código de pareamento. O app troca o código por um token permanente e já começa a
   gravar.
4. Conceda as permissões de Câmera e Microfone quando solicitado, e aceite a isenção de otimização de
   bateria (necessária pra rodar 24h sem o Android matar o processo).
5. Deixe o celular fixo, na tomada, apontando pro ambiente.

Se o celular for trocado/perdido, use "🔄 Resetar" na tela de câmeras do painel — isso invalida o token
antigo e gera um novo código, sem precisar apagar a câmera e recriar o histórico de gravações.

## Build

Sem SDK Android instalado localmente — o APK é compilado via GitHub Actions (`.github/workflows/build-apk.yml`)
a cada push na branch `main`, publicado automaticamente como Release (`build-N`).

## Limitações conhecidas

- Latência ao vivo de ~15-20s (arquitetura por segmentos HLS, não WebRTC).
- APK de debug, não assinado para Play Store — instalação via "fontes desconhecidas".
- Qualidade de vídeo fixa em SD (CameraX `Quality.SD`) pra manter o consumo de dados/armazenamento razoável
  rodando 24/7.
