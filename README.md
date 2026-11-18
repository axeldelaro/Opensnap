# 👻🛡️ OpenSnap — Messagerie Instantanée & Éphémère 24h Souveraine (Android Natif)

**OpenSnap** est une application Android 100 % native (Kotlin + Jetpack Compose, API 26 à 35 — Android 8.0 à Android 15+) de messagerie instantanée chiffrée de bout en bout (**E2EE**), éphémère sur **24 heures**, et **sans aucun serveur propriétaire payant** (zéro Firebase, zéro AWS, zéro interface Web/PWA).

## Architecture de Sécurité & Temps Réel

1. **Daemon Réseau Dédié (`android:process=":network_daemon"`) & Zéro Polling**
   - `OpenSnapDaemonService` (`ForegroundService` de type `remoteMessaging|specialUse` avec `START_STICKY` et auto-relance `AlarmManager` sur `onTaskRemoved`).
   - Maintient en permanence un pool multiplexé de connexions **WebSocket TLS 1.3 (WSS)** vers des relais décentralisés Nostr + un canal direct **WebRTC P2P (`DataChannel` DTLS `opensnap-fastlane`)** dès que deux amis sont en ligne simultanément (< 30 ms).
   - **Heartbeat adaptatif** calé sur le transport radio (`25s` en 4G/5G cellulaire contre les timeouts NAT opérateurs, `45s` en Wi-Fi) et verrouillage chirurgical `PartialWakeLock` + `WifiLock` uniquement durant le déchiffrement d'un paquet entrant.
   - Notifications natives Android 13/14/15 `MessagingStyle` avec avatar cryptographique, **Réponse directe (`RemoteInput`)** depuis la barre de notification, bouton **« Marquer comme lu »**, actions **Accepter / Refuser** pour les demandes d'amis, et `fullScreenIntent` sur écran verrouillé pour les appels audio/vidéo P2P WebRTC.

2. **Compte Souverain Déterministe & Cryptographie Appliquée**
   - Inscription / Connexion par **Identifiant + Mot de passe fort** ou **Phrase secrète BIP-39 (12 mots)**.
   - Dérivation déterministe **Argon2id** + **HKDF-SHA256** générant :
     - `Ed25519` (signature d'identité et Tag auto-certifié `OS-XXXX-XXXX`),
     - `X25519` (échange ECDH + rotation de clés **Forward Secrecy** par message),
     - `secp256k1 BIP-340` (routage Nostr NIP-01/NIP-40 et masquage des métadonnées d'expéditeur **NIP-59 Gift Wrap / Sealed Sender**),
     - Sauvegarde chiffrée **NIP-78** de la liste d'amis permettant de retrouver son compte et ses contacts en changeant de téléphone sans serveur central.
   - Chiffrement authentifié **XChaCha20-Poly1305** (clés 256 bits, nonces 192 bits) conforme au RFC `draft-irtf-cfrg-xchacha`.

3. **Rétention Stricte 24h00 & Purge Cryptographique**
   - Stockage local chiffré **SQLCipher AES-256** (`PRAGMA secure_delete = ON`) + coffre média chiffré par fichier (`.osenc`) déchiffré exclusivement en RAM (`MediaDataSource` mémoire pour les vocaux).
   - Relais Store-and-Forward 24h (`["expiration", ts + 86400]` NIP-40) pour délivrer instantanément les messages envoyés pendant que le téléphone du destinataire était éteint.
   - **Purge cryptographique irréversible (`SecureFileShredder`)** écrasant les blocs flash (CSPRNG + zéros + `fsync`) dès 24h00 d'existence (ou immédiatement après fermeture d'un Snap **Vue unique**).

4. **Bouclier Cyber & Sécurité Physique**
   - `FLAG_SECURE` activé sur toutes les fenêtres (blocage captures et enregistrements d'écran).
   - Nettoyage intégral des métadonnées **EXIF / GPS** (`ExifSanitizer`) avant chiffrement.
   - Verrouillage biométrique (`BiometricPrompt`) adossé au **StrongBox / TEE Android Keystore** et **Code PIN Leurre (Duress PIN / Panic Wipe)** détruisant instantanément SQLCipher et le Keystore en cas de contrainte.
   - **Liste blanche cryptographique stricte** + **Proof-of-Work NIP-13** + **Anti-Replay** : seuls les amis mutuellement acceptés peuvent déclencher une notification ou déposer un paquet dans la boîte de réception.

## Téléchargement & CI/CD GitHub Actions

Chaque push sur `main` déclenche `.github/workflows/build-apk.yml` qui exécute la suite de tests cryptographiques JUnit, compile l'APK en mode Release (R8), le signe (V1 + V2 + V3) et publie automatiquement **`OpenSnap-latest.apk`** dans les **Releases** et **Artifacts** du dépôt `https://github.com/axeldelaro/Opensnap`.
