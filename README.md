# Prothea

**Scanner 3D du buste, 100 % local, pour la conception de prothèses mammaires externes imprimées en 3D.**

Prothea permet aux femmes ayant subi une mastectomie de réaliser elles-mêmes — chez elles, en toute intimité — un relevé 3D de leur paroi thoracique, base de conception d'une prothèse externe sur mesure (impression TPU souple type Filaflex / silicone).

## Pourquoi c'est différent

| | Solutions existantes | Prothea |
|---|---|---|
| Données | Upload cloud obligatoire | **Aucune donnée ne quitte le téléphone** |
| Permission réseau | Oui | **Aucune** (vérifiable dans le manifest) |
| Matériel | Scanner dédié coûteux | N'importe quel smartphone Android |
| Métadonnées | Serveur tiers | Chiffrées AES-256-GCM (Keystore) |

## Fonctionnement

1. **Capture guidée** : un anneau de 16 secteurs s'affiche à l'écran. La personne (ou un proche) tourne lentement autour de la patiente ; chaque secteur validé passe au vert. ARCore fournit la pose métrique quand disponible, sinon les capteurs inertiels prennent le relais.
   - **Caméra arrière** : scan par un proche, avec nuage de points 3D ARCore si l'appareil le supporte.
   - **Caméra avant (auto-scan)** : la patiente se scanne seule, téléphone à bout de bras, en tournant sur elle-même — intimité totale. La profondeur est alors estimée par **IA embarquée** (MiDaS v2.1, TFLite on-device, délégué GPU quand disponible) : aperçu live en surimpression + carte de profondeur PNG jointe à chaque photo. ⚠️ Profondeur *relative* (sans échelle métrique) — l'échelle se recale ensuite sur les photos / une référence connue.
2. **Profondeur réelle** : sur les appareils compatibles ARCore Depth (ToF ou depth-from-motion), un **nuage de points 3D à l'échelle réelle** (±1–3 mm) est accumulé pendant le scan et exporté en PLY.
3. **Photos sources** : chaque photo est horodatée avec son azimut — exploitable en photogrammétrie (Meshroom/COLMAP en local) pour une précision supérieure.
4. **Export** : ZIP de la session via le sélecteur de fichiers Android. Possibilité de supprimer les photos sources après reconstruction (option vie privée).

## Sécurité & vie privée

- ❌ Pas de permission `INTERNET` dans le manifest — l'app **ne peut physiquement pas** envoyer de données
- 📁 Stockage dans le répertoire privé de l'app (sandbox Android)
- 🔐 Métadonnées chiffrées (`EncryptedFile`, clé dans Android Keystore)
- 🗑️ Suppression des photos sources en un geste

## Build

```bash
./gradlew assembleRelease
```
Un workflow GitHub Actions compile l'APK à chaque push (onglet *Actions* → artifacts) et crée une release à la demande.

## Roadmap

- [x] Reconstruction surfacique on-device : PLY → **STL binaire (mm)** directement dans l'app (nettoyage du décor + reconstruction cylindrique + capuchons, prêt pour PrusaSlicer/Bambu Studio)
- [ ] Photogrammétrie on-device (OpenMVS via NDK) pour précision métrologique depuis les photos
- [ ] Mode miroir : scanner le côté sain et symétriser pour la prothèse du côté opéré
- [ ] Génération paramétrique de la coque prothèse (offset de surface + épaisseur)
- [ ] Guide de pose AR (bras à 45°, respiration à mi-expiration)
- [ ] Partenariat clinique (Ligue contre le cancer) pour validation métrologique

## Avertissement

Prothea est un outil d'aide à la conception, **pas un dispositif médical certifié**. Toute prothèse définitive doit être validée par un prothésiste/orthoprothésiste diplômé.
