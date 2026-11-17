# ScreenLink

Comparte la pantalla del PC al móvil (o entre dos PCs) con un código de 6 cifras, desde cualquier red.

- Web estática, un único `index.html`: no hace falta backend.
- Vídeo P2P por WebRTC, cifrado (DTLS-SRTP). Hasta 60 fps y 1440p, con bitrate alto y buffer de recepción mínimo para reducir la latencia.
- Señalización con el servidor público gratuito de PeerJS (`0.peerjs.com`).
- QR y enlace directo (`?c=123456`), varios espectadores, pantalla completa, audio del sistema/pestaña y estadísticas en vivo.

## Uso
1. En el PC: **Empezar a compartir**, eliges la pantalla o ventana y aparece el código.
2. En el móvil: escribes el código o escaneas el QR.

> Los navegadores de móvil no permiten capturar la pantalla, así que el móvil solo puede **ver**.

## TURN (necesario con datos móviles / NAT estricto)
La web y la app leen los servidores ICE de `config.json` (en la raíz de este repo). Basta con editarlo; no hay que recompilar la app.

**Opción A: Metered / Open Relay (gratis, 20 GB al mes)**
1. Crea una cuenta en https://www.metered.ca/stun-turn y crea una app TURN.
2. Copia la URL de credenciales (`https://TUAPP.metered.live/api/v1/turn/credentials?apiKey=...`).
3. Pégala en `turnCredentialsUrl` dentro de `config.json`.

**Opción B: coturn en tu VPS (sin límite de datos)**
```bash
docker run -d --name coturn --network host --restart unless-stopped coturn/coturn \
  -n --log-file=stdout --lt-cred-mech --fingerprint \
  --user=screenlink:CAMBIA_ESTA_PASS --realm=blockhost.es \
  --listening-port=3478 --min-port=49160 --max-port=49200 \
  --external-ip=$(curl -s https://api.ipify.org)
```
Abre en el firewall 3478 (tcp/udp) y 49160-49200 (udp). Luego añade a `iceServers` en `config.json`:
```json
{ "urls": ["turn:TU_IP_O_DOMINIO:3478?transport=udp", "turn:TU_IP_O_DOMINIO:3478?transport=tcp"],
  "username": "screenlink", "credential": "CAMBIA_ESTA_PASS" }
```

## Señalización propia (opcional)
```bash
npx peer --port 9000
```
Y en `index.html`: `PEER_SERVER = { host: 'peer.blockhost.es', port: 443, path: '/', secure: true }` (con nginx haciendo proxy WebSocket a :9000).
