# Suivi Formateur — Android natif

Application Kotlin pour Honor Magic V2 : fiches enregistrées localement, heures (manuel/carte de pointage), congé/repos, frais, prime RH, signature, export PDF et partage par e-mail via GMX ou une autre application installée.

## Générer l'APK

1. Ouvrir l'onglet **Actions** de ce dépôt.
2. Choisir **Build Android APK**, puis **Run workflow** (ou consulter l'exécution déclenchée automatiquement par un push sur main).
3. Une fois le build réussi, ouvrir son exécution et télécharger **SuiviFormateur-APK** dans **Artifacts**.
4. Décompresser l'archive pour obtenir `app-debug.apk` et l'installer sur Android.

Il s'agit d'une version de développement non testée sur appareil. L'envoi GMX nécessite une connexion et l'application GMX configurée. Les données restent locales, sans synchronisation ni sauvegarde distante. Le choix « Carte pointage » n'est pas une lecture physique de carte.
