# Falaí

Mensageiro da família. Um celular antigo fica em casa como servidor, e os celulares e tablets da família se conectam a ele pela internet através do Cloudflare Tunnel.

## Estado atual: 0.10 (grupos completos, responder, reagir, tiques, modo escuro)

| Parte | O que faz | Testado |
|---|---|---|
| `shared` | Protocolo (JSON) e acesso ao banco, usados pelo servidor e pelo app | Pelos testes abaixo |
| `server-core` | Servidor em Kotlin puro: pareamento por convite, token por aparelho, WebSocket com mensagens de texto em tempo real, histórico completo com sync em lotes, conversas diretas e grupos, chamadas (apresenta os aparelhos e repassa os recados do WebRTC) | Sim, testes automatizados |
| `server-android` | App Android que mantém o servidor ligado: serviço em primeiro plano, wake lock, liga sozinho no boot, tela com status e código do admin | Sim, no celular (M1) |
| `client-core` | "Motor" do app da família: pareamento, conexão com reconexão automática, fila de envio sem internet, sync do histórico, banco local | Sim, 11 testes contra o servidor de verdade |
| `app` | App da família (Jetpack Compose): pareamento, lista de conversas, chat, nova conversa/grupo, convites (admin) | Telas conferidas por compilação; precisa ser testado no celular |

**Mídia (M3):** o arquivo original sobe em pedaços de 8 MB (com retomada se a conexão cair) e o servidor confere o SHA-256: chega idêntico, sem recompressão. Quem recebe só baixa quando a mensagem aparece na tela (fotos e áudios até 25 MB) ou quando toca (vídeos e arquivos). No celular-servidor, os arquivos ficam no cartão microSD, se houver.

> ⚠️ A pasta de mídia do servidor é apagada se o app do servidor for **desinstalado**. Atualizar o app não apaga nada.

**Pela internet (M4):** o servidor fica acessível em `https://falai.SEUDOMINIO` pela Cloudflare Tunnel. Os convites viram **link**: o administrador toca em *Convidar → Enviar convite* e manda pelo WhatsApp. A pessoa abre o link, baixa o app (o servidor oferece o arquivo) e toca em *Abrir o convite no Falaí*: só precisa digitar o nome.

**Mais simples (M5):**
- O convite não precisa de nada digitado: a página do convite guarda o link ao tocar em *Baixar e instalar*, e o app encontra o convite sozinho ao abrir pela primeira vez. A pessoa só escolhe o nome.
- Fotos e vídeos abrem dentro do app, em tela cheia (foto com zoom de dois dedos e toque duplo; vídeo com os controles do Android). *Abrir com…* continua lá para usar outro app.
- **Configurações** (engrenagem no topo): trocar a foto de perfil e o nome. A família vê na hora, na lista de conversas e no topo do chat. Mostra também a versão, o servidor e a conexão.
- Seta de voltar maior e mais grossa.
- **Salvar, encaminhar e compartilhar:** segurando o dedo numa mensagem (ou no ⋮ / *Salvar* da tela cheia). *Salvar* põe uma cópia na galeria (pasta Falaí). *Encaminhar* manda para outra conversa sem subir o arquivo de novo. *Compartilhar* manda para outros apps (WhatsApp, e-mail…).

**No computador (M6):** abra `https://falai.SEUDOMINIO/web` no navegador. A página mostra um QR Code. No celular, vá em *Configurações → Falaí no computador → Ler o QR Code* (ou digite o código de 8 letras). As conversas aparecem no navegador: ler, escrever, ver e mandar fotos, vídeos, áudios e arquivos (até 1 GB; arraste e solte ou Ctrl+V), salvar, avisos do navegador. O navegador vira um "aparelho" da pessoa: a chave fica num cookie protegido (o JavaScript da página não lê, e outros sites não usam). *Sair* desliga o computador.

**Editor de fotos e perfil no computador (0.7):**
- Antes de mandar uma foto (escolhida, tirada com a câmera, arrastada ou colada com Ctrl+V), abre um editor como o do WhatsApp: *Recortar* (arrastando os cantos), *Girar*, *Desenhar* (com cores), *Texto* (arrastável, com cores e tamanho), *Emoji*, *Desfazer* e legenda. No celular e no computador. Se não mexer em nada, vai a foto original, idêntica.
- No computador, a engrenagem (ou a sua foto no topo) abre as **Configurações**: trocar ou tirar a foto de perfil e mudar o nome, igual ao celular.

**Chamadas e cor nova (0.8):**
- Cor principal **azul-petróleo** no app, na web e na página do convite.
- **Chamadas de voz e vídeo** pelo botão 📞 / 🎥 no topo da conversa, no celular e no computador. Também em **grupo** (até 6 pessoas): quem não atendeu na hora vê *Chamada em andamento · toque para entrar*.
- Durante a chamada: microfone, câmera (liga e desliga a qualquer hora, até numa chamada de voz), virar a câmera, viva-voz / ouvido, desligar.
- **Mostrar a tela**: no computador (Chrome/Edge) e no celular (Android pede confirmação: *Começar agora*).
- O som e a imagem vão **direto** de um aparelho para o outro (WebRTC); o servidor só apresenta os dois. Para achar o caminho usamos STUN grátis (Google e Cloudflare). Se a chamada ficar em "conectando…" entre dois celulares no 4G, o administrador liga o **Ajudante de chamadas** (TURN da Cloudflare, grátis até 1.000 GB por mês) em *Configurações → Chamadas*.
- **Tocar com o app fechado** (ligado de fábrica, em *Configurações → Chamadas*): um aviso discreto "Falaí conectado" mantém a conexão. Recomendado tocar em *Não deixar o Android desligar o Falaí*.

**Cara profissional (0.9):**
- **O aparelho é reconhecido:** se a pessoa desinstalar e instalar o Falaí de novo (ou apagar os dados), o app entra sozinho na mesma conta, sem convite. O app usa o ANDROID_ID (o número que o Android dá ao app neste aparelho; MAC e número de série não são mais permitidos) e o servidor guarda só um resumo dele. Um convite novo usado num aparelho conhecido também não cria pessoa repetida.
- **Convite e app já com o código:** o servidor "carimba" o convite dentro do arquivo do app na hora do download (na área da assinatura que o Android ignora; a assinatura continua válida). Ao abrir, o app já tem o convite: a pessoa só confirma o nome. O administrador pode escrever o nome da pessoa ao convidar, e ele já vem preenchido. O app baixado em `/app/falai.apk` vem com o endereço do servidor.
- **Apagar conversa** (segurar o dedo na conversa, ou ⋮ dentro dela; no computador, ⋮ ou botão direito): some da lista em todos os seus aparelhos; para os outros nada muda; se chegar mensagem nova, ela volta só com as novas.
- **Tirar alguém da família** (só o administrador: ⋮ na conversa, ou *Configurações → Pessoas da família*): os aparelhos da pessoa são desligados na hora; as mensagens antigas ficam.
- **Trazer de volta** (0.9.1, só o administrador: *Configurações → Pessoas da família → Fora da família*, ou ⋮ na conversa): a pessoa volta com os mesmos aparelhos, sem convite novo; é só ela abrir o Falaí.
- **Ajustar a foto de perfil** (0.9.2, celular e computador): depois de escolher a foto, arrasta, aproxima ou afasta (dois dedos, rodinha do mouse ou barrinha) e gira, dentro de um círculo; sai um quadrado 512×512.
- **Atualizar o app** (0.9.3): quando o administrador abre o Falaí com uma versão nova, ela vai sozinha para o servidor, e os outros celulares mostram a faixa **"Versão nova do Falaí → Atualizar"** (baixa e instala pelo próprio Falaí; na 1ª vez o Android pede "Permitir desta fonte"). A página do convite e a página `/app` têm o passo a passo ilustrado dos avisos do Android (Play Protect → "Mais detalhes" → "Instalar mesmo assim").
- **Importante:** gere o APK sempre no mesmo computador (mesma assinatura) e guarde uma cópia de `C:\Users\<você>\.android\debug.keystore`. Com outra assinatura, o Android recusa a atualização.
- **Fundo das conversas:** cor calma com desenhinhos clarinhos (7 cores, desenhos liga/desliga, ou uma foto da galeria). *Configurações → Fundo das conversas* (no computador, na engrenagem).
- **Ícone próprio** (balão com a família dentro) no app, no servidor, nos avisos, na tela de abertura; favicon, ícone de "instalar" e página do convite com a marca no computador.
- Configurações mais limpas: só conexão e versão.

**0.10:**
- **Mexer no grupo** (celular: toque no nome do grupo ou ⋮ → *Dados do grupo*; computador: clique no nome): nome, foto (com ajuste), pôr pessoas, tirar (quem criou ou o administrador) e sair. Quem sai continua lendo as antigas, mas não escreve. Avisos no meio da conversa ("Ana adicionou Bia").
- **Computador cria conversa e grupo** (botão ✏️ no topo da lista).
- **Responder, reagir e apagar para todos**: segurar a mensagem no celular; no computador, botão ⌄ ou clique direito.
- **Tiques**: ✓ enviada, ✓✓ cinza entregue, ✓✓ azul lida (no grupo: quando todos).
- **Modo escuro**: *Configurações → Aparência* (igual ao celular/computador, claro ou escuro).
- Protocolo 2 (`/ws?p=2`); aparelhos com app antigo continuam funcionando (não veem reações; apagada aparece como "🚫 Mensagem apagada").

Ainda não tem: notificações pelo Google (FCM), editor de vídeo, criptografia no app.

## Rodar os testes (PC)

Precisa só de um JDK 17 ou mais novo.

```bash
./gradlew :server-core:test :client-core:test
```

## Testar o app da família em casa (sem Cloudflare ainda)

O servidor só aceita conexões do próprio celular-servidor, então em casa o teste é assim:

- **No próprio celular-servidor:** instale também o app `app` e use o endereço `http://127.0.0.1:8443`.
- **No emulador do Android Studio:** com o celular-servidor no cabo, rode `adb forward tcp:8443 tcp:8443` e use o endereço `http://10.0.2.2:8443` (o emulador enxerga o PC nesse endereço, e o PC repassa para o celular).

Assim dá para conversar entre o celular-servidor e o emulador.

## Rodar o servidor no PC (para experimentar)

```bash
./gradlew :server-core:run --args="--data ./data --port 8443"
```

Na primeira vez, aparece no terminal o **código do administrador** (vale 24 horas). Para parear um aparelho:

```bash
curl -X POST http://127.0.0.1:8443/api/pair \
  -d '{"code":"SEUCODIGO","deviceName":"PC","userName":"Chefe"}'
```

## Instalar no celular-servidor

1. Abra a pasta do projeto no **Android Studio**. Ele cria o `local.properties` e o módulo `server-android` aparece.
2. Se o Android Studio sugerir atualizar o Android Gradle Plugin, pode aceitar.
3. Ligue o celular-servidor no cabo com a depuração USB ativada e rode o app `server-android`.
4. Na tela do app:
   - toque em **"Não otimizar bateria"** e confirme;
   - anote o **código do administrador**.
5. Confira no PC (com o celular no cabo):
   ```bash
   adb forward tcp:8443 tcp:8443
   curl http://127.0.0.1:8443/health     # {"ok":true,"version":1}
   ```

O servidor só aceita conexões do próprio celular (`127.0.0.1`). Quem chega de fora passa pelo `cloudflared`, que roda no mesmo aparelho.

### Cloudflare Tunnel (acesso de fora)

1. Na Cloudflare (plano grátis), adicione o seu domínio e troque os nameservers na Namecheap pelos dois que a Cloudflare indicar ("Custom DNS").
2. No celular-servidor, instale o **F-Droid** e, por ele, o **Termux** e o **Termux:Boot** (os dois do F-Droid; abra o Termux:Boot uma vez). No Termux:
   ```bash
   pkg update -y && pkg install -y cloudflared curl
   cloudflared tunnel login                     # abre o navegador: escolha o domínio e autorize
   cloudflared tunnel create falai
   cloudflared tunnel route dns falai falai.SEUDOMINIO
   ```
3. Para a ponte ligar sozinha quando o celular liga (e religar se cair):
   ```bash
   curl -s http://127.0.0.1:8443/setup/tunel | sh
   ```
   O registro fica em `~/falai-tunel.log`. Deixe a bateria do Termux **sem restrição**.
4. Teste de qualquer lugar: `https://falai.SEUDOMINIO/health`.

Na primeira vez em *Convidar*, o app pede o endereço (ex.: `falai.SEUDOMINIO`); a partir daí, os convites saem como link. Cada convite vale 7 dias e serve para uma pessoa.

### Cuidado com a bateria

Um celular ligado na tomada 24 horas por meses pode inchar a bateria. Se o LineageOS do aparelho tiver **limite de carga**, ative em 80%. Se não tiver, use um timer na tomada.

## Protocolo (v1)

**HTTP**
- `GET /health` → `{"ok":true,"version":1}`
- `GET /convite/{codigo}` → página do convite (abre o app com `falai://convite?s=SERVIDOR&c=CODIGO`)
- `GET /app/falai.apk` → arquivo do app; `GET /api/app-apk` → versão disponível; `PUT /api/app-apk?versionCode=N&versionName=X` (só admin)
- `GET /setup/tunel` → instalador do Termux:Boot (só no próprio celular-servidor)
- `POST /api/pair` `{code, deviceName, userName?, attemptId?}` → `{userId, deviceId, token, isAdmin}`
  - convite de uso único, vale 24h; 10 erros por IP em 10 minutos bloqueiam por um tempo
  - o primeiro pareamento (com o código do administrador) vira administrador

**WebSocket** `/ws` com o cabeçalho `Authorization: Bearer <token>`. Cada quadro é JSON com `type`.

| Cliente → servidor | Resposta |
|---|---|
| (ao conectar) | `hello {user, deviceId, conversations, lastSeq}` |
| `send {id, conversationId, body}` | `ack {id, seq, createdAt}`; os outros aparelhos recebem `message` |
| `sync {afterSeq}` | `sync_batch {messages}` (0 ou mais) e depois `sync_done {lastSeq}` |
| `create_conversation {requestId, kind, title?, memberIds}` | `conversation`; os outros membros também recebem |
| `list_users {requestId}` | `users` |
| `create_invite {requestId, forUserId?}` (só admin) | `invite {code, expiresAt}` |
| `ping {requestId}` | `pong` |

Erros chegam como `error {code, message, requestId?}`.

Regras importantes:
- o `id` da mensagem é um UUID gerado no aparelho. Reenviar o mesmo `id` não duplica, então o app pode reenviar à vontade depois de uma queda;
- `seq` é a ordem no servidor. O aparelho guarda o maior `seq` que já viu e, ao reconectar, pede `sync` a partir dele;
- `create_invite` com `forUserId` liga **mais um aparelho** à mesma pessoa, que recebe todo o histórico.
