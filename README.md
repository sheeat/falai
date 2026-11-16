# Falaí

Mensageiro da família. Um celular antigo fica em casa como servidor, e os celulares e tablets da família se conectam a ele pela internet através do Cloudflare Tunnel.

## Estado atual: 0.7 (editor de fotos)

| Parte | O que faz | Testado |
|---|---|---|
| `shared` | Protocolo (JSON) e acesso ao banco, usados pelo servidor e pelo app | Pelos testes abaixo |
| `server-core` | Servidor em Kotlin puro: pareamento por convite, token por aparelho, WebSocket com mensagens de texto em tempo real, histórico completo com sync em lotes, conversas diretas e grupos | Sim, 14 testes automatizados |
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

Ainda não tem: notificações com o app fechado (FCM), editor de vídeo, chamadas, criptografia no app.

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
