# AethoriaStats

Un petit plugin Bukkit (Mohist 1.20.1, Java 17) qui envoie les statistiques du serveur dans un **Gist GitHub**, et une page web (`site/index.html`) qui les affiche : joueurs en ligne, TPS, rétention, classements, activité, mondes, machine.

## Installation du plugin

1. Crée un Gist **secret ou public** contenant un fichier `stats.json` (contenu `{}`).
2. Crée un token GitHub *fine-grained* avec **une seule permission : « Gists : read and write »** (rien d'autre).
3. Copie `plugin/build/AethoriaStats.jar` dans `plugins/` du serveur, puis démarre le serveur une fois.
4. Remplis `plugins/AethoriaStats/config.yml` :
   - `gist-id` : la fin de l'adresse du Gist ;
   - `github-token` : le token de l'étape 2 ;
   - `gist-file` (défaut `stats.json`), `send-interval-seconds` (défaut 120), `hide-player-names` (défaut `false`), `timezone` (vide = fuseau du serveur).
5. Dans le jeu ou la console : `/astats reload`, puis `/astats send` pour tester. `/astats` seul montre l'état de l'envoi.

Aucune adresse IP n'est écrite dans le fichier envoyé. Les données persistantes sont dans `plugins/AethoriaStats/data.json`. Avec AuthMe, une session ne compte qu'une fois le joueur connecté.

## La page

Dans `site/index.html`, en haut du script, remplace `GIST_URL` par :

`https://gist.githubusercontent.com/<ton-pseudo>/<gist-id>/raw/stats.json`

Héberge ensuite le dossier `site/` (GitHub Pages, par exemple). GitHub peut garder le fichier « raw » en cache quelques minutes : la page considère le serveur « en ligne » si les données ont moins de 8 minutes (`FRESH_MIN`).
Pour voir un exemple sans serveur : ouvre la page avec `?demo=1` depuis un petit serveur web local (par exemple `python -m http.server` dans `site/`). On peut aussi tester une autre adresse avec `?src=<url>`.

## Reconstruire

Il faut un JDK 17 ou plus. Télécharge `spigot-api-1.20.1-R0.1-SNAPSHOT.jar` (dépôt `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`) sous le nom `plugin/lib/spigot-api.jar`, puis :

```
plugin/build.sh   # crée plugin/build/AethoriaStats.jar (classes en Java 17)
plugin/test.sh    # simulation sans serveur, vérifie le JSON, régénère site/stats.sample.json
```
