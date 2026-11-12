# LAN Vitals

Tableau de bord Android pour suivre un PC Linux en direct sur le même réseau local. Affiche le processeur, la mémoire, les débits réseau, l’espace disque et le temps de fonctionnement. Les relevés arrivent environ chaque seconde. Sur tablette, les cartes s’affichent sur deux colonnes lorsque la largeur le permet.

## Démarrage

1. Téléchargez le script Linux et l’APK signé depuis [Releases](https://github.com/amapi/lan-vitals/releases).
2. Sur le PC, avec Python 3, OpenSSL (1.1.1+) et `ip` (iproute2) disponibles, lancez le serveur avec votre mot de passe.

   ```bash
   python3 lan-vitals-server --password 'votre-mot-de-passe'
   ```

   Cette variante évite de laisser le mot de passe dans l’historique du shell et les arguments du processus.

   ```bash
   (read -rs -p 'Mot de passe LAN Vitals : ' LAN_VITALS_PASSWORD
    printf '\n'
    export LAN_VITALS_PASSWORD
    python3 lan-vitals-server)
   ```

3. Installez l’APK sur Android 6.0 ou ultérieur. Connectez la tablette au même réseau IPv4 que le PC, ouvrez LAN Vitals, saisissez le même mot de passe et touchez **Trouver le serveur**.
4. Au premier appairage, vérifiez que l’empreinte affichée sur la tablette correspond à celle du terminal du PC. Confirmez la correspondance pour autoriser l’envoi du mot de passe.

Le serveur conserve son identité TLS dans `~/.local/state/lan-vitals/identity.pem` par défaut. Si vous changez de serveur ou régénérez ce fichier, utilisez **Oublier l’appairage** avant d’enregistrer la nouvelle empreinte. Le client conserve l’empreinte après authentification. Il garde le mot de passe en mémoire le temps de la session pour les reconnexions.

## Réseau et options

La découverte utilise la diffusion UDP IPv4 sur le port **48321**. Le flux chiffré utilise le port TCP **48322** par défaut. Le PC peut être en Ethernet et la tablette en Wi-Fi si les deux partagent le même sous-réseau. Si la découverte échoue, vérifiez les règles du pare-feu du PC et l’isolation des clients Wi-Fi, fréquente sur les réseaux invités.

- `--bind 192.168.1.10` limite le flux à une adresse IPv4 locale. Par défaut, le serveur accepte les clients de ses sous-réseaux IPv4 actifs.
- `--port 48323` change le port TCP annoncé aux clients. Le port de découverte reste fixe.
- `--disk /mnt/data` affiche l’espace du système de fichiers contenant ce chemin.
- `--state-dir chemin` change le dossier de l’identité TLS.

Le processeur et les débits utilisent les deltas des compteurs Linux. La mémoire utilisée exclut la mémoire récupérable comptabilisée dans `MemAvailable`. Les débits cumulent les interfaces Ethernet et Wi-Fi actives ayant une adresse IPv4 de diffusion, y compris les interfaces virtuelles répondant à ces critères. Les graphiques affichent jusqu’à 60 relevés.

## Compiler l’APK

Prérequis Linux de compilation : JDK 17, Android SDK Platform 36, Android Build Tools 36.0.0 et `zip`. Le client utilise les vues Android natives et ne télécharge aucune dépendance applicative.

```bash
keytool -genkeypair -alias lan-vitals -keyalg RSA -keysize 3072 \
  -validity 10000 -dname 'CN=LAN Vitals' -storetype PKCS12 -keystore lan-vitals.p12
export ANDROID_HOME=/chemin/vers/android-sdk
export LAN_VITALS_KEYSTORE=/chemin/vers/lan-vitals.p12
export LAN_VITALS_KEYSTORE_PASSWORD_FILE=/chemin/vers/fichier-mot-de-passe
android/build.sh
```

Le fichier de mot de passe contient le mot de passe du keystore sur sa première ligne. Gardez-le et le keystore hors du dépôt. Réutilisez la même clé pour signer les mises à jour d’une installation existante. Le script affiche le chemin de l’APK.

## Tester le serveur

```bash
python3 tests/smoke.py
```

Le test requiert une interface IPv4 active et les ports de découverte et de flux libres. Il vérifie la découverte, la continuité du certificat, le rejet d’un mauvais mot de passe et la réception de plusieurs relevés.
