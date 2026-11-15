# Falaí

Mensageiro da família. Um celular antigo fica em casa como servidor, e os celulares e tablets da família se conectam a ele pela internet através do Cloudflare Tunnel.

## Estado atual: M1 (servidor básico)

| Parte | O que faz | Testado |
|---|---|---|
| `server-core` | Servidor em Kotlin puro: pareamento por convite, token por aparelho, WebSocket com mensagens de texto em tempo real, histórico completo com sync em lotes, conversas diretas e grupos | Sim, 14 testes automatizados |
| `server-android` | App Android que mantém o servidor ligado: serviço em primeiro plano, wake lock, liga sozinho no boot, tela com status e código do admin | Não. Precisa ser testado no celular |

Ainda não tem: app da família (M2), mídia (M3), chamadas (M4), FCM, criptografia no app.

## Rodar os testes (PC)

Precisa só de um JDK 17 ou mais novo.

```bash
./gradlew :server-core:test
```

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

1. Na Cloudflare (plano grátis), adicione o seu domínio e troque os nameservers na Namecheap pelos dois que a Cloudflare indicar.
2. No celular-servidor, instale o **Termux** pelo F-Droid (a versão da Play Store está desatualizada) e rode:
   ```bash
   pkg install cloudflared
   cloudflared tunnel login
   cloudflared tunnel create falai
   cloudflared tunnel route dns falai falai.SEUDOMINIO.com
   cloudflared tunnel run --url http://127.0.0.1:8443 falai
   ```
3. Teste de qualquer lugar: `https://falai.SEUDOMINIO.com/health`.

Iniciar o túnel sozinho no boot (Termux:Boot) e o wake lock do Termux entram em uma próxima etapa.

### Cuidado com a bateria

Um celular ligado na tomada 24 horas por meses pode inchar a bateria. Se o LineageOS do aparelho tiver **limite de carga**, ative em 80%. Se não tiver, use um timer na tomada.

## Protocolo (v1)

**HTTP**
- `GET /health` → `{"ok":true,"version":1}`
- `POST /api/pair` `{code, deviceName, userName?}` → `{userId, deviceId, token, isAdmin}`
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
