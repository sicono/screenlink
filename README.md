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

## Redes distintas / NAT estricto
Casi siempre funciona solo con STUN. Si alguno de los dos está en una red 4G/5G con CGNAT estricto o en una red corporativa, hace falta un servidor **TURN**. Con coturn en tu VPS:

```bash
apt install coturn
# /etc/turnserver.conf
listening-port=3478
tls-listening-port=5349
fingerprint
lt-cred-mech
user=screenlink:CAMBIA_ESTA_PASS
realm=blockhost.es
cert=/etc/letsencrypt/live/turn.blockhost.es/fullchain.pem
pkey=/etc/letsencrypt/live/turn.blockhost.es/privkey.pem
min-port=49160
max-port=49200
```
Abre en el firewall 3478 (tcp/udp), 5349 (tcp) y 49160-49200 (udp). Después rellena `TURN` al principio del `<script>` de `index.html`.

## Señalización propia (opcional)
```bash
npx peer --port 9000
```
Y en `index.html`: `PEER_SERVER = { host: 'peer.blockhost.es', port: 443, path: '/', secure: true }` (con nginx haciendo proxy WebSocket a :9000).
