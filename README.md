# AethoriaStats

Un plugin Bukkit léger (Mohist 1.20.1, Java 17) et une page web (`site/index.html`).

- Le plugin collecte les statistiques et les sert sur une **petite API HTTP** (JDK seul, aucune dépendance), écoutant **uniquement sur `127.0.0.1:8765`**.
- La page affiche les stats publiques (`GET /api/stats`) et, après connexion, un **panneau d'administration** : console en direct, soigner, nourrir, exclure, bannir, muet, immobiliser, annonces, météo, heure, journal d'audit.

## Installation du plugin

1. Copie `plugin/build/AethoriaStats.jar` dans `plugins/`, démarre le serveur une fois.
2. Dans la **console du serveur** (pas en jeu), crée un compte : `/astats setpass <nom> <mot de passe> [admin|modo]` (10 caractères minimum, rôle `admin` par défaut). Le mot de passe n'est jamais stocké : seul un hash PBKDF2-SHA256 salé est écrit dans `config.yml`.
3. Mets un serveur web HTTPS devant l'API (Caddy, par exemple) :
   ```
   141-11-103-141.sslip.io {
       reverse_proxy 127.0.0.1:8765
   }
   ```
   Pour la console en direct, aucun réglage spécial : l'API répond en attente longue (25 s au plus), sans mise en mémoire tampon à désactiver.
4. `config.yml` > `api.allowed-origins` : le site autorisé à appeler l'API (par défaut `https://matheokost6-arch.github.io`). `http://localhost:*` est toujours accepté pour les essais.

Autres commandes : `/astats` (état), `/astats reload` (relit `config.yml` et les comptes ; un changement de port demande un redémarrage).

## Rôles

| Rôle | Peut |
|---|---|
| `admin` | tout, y compris la commande console libre et le bannissement définitif |
| `modo` | tout sauf la console libre et le bannissement définitif (un ban doit avoir une durée) |

## Sécurité

- L'API n'écoute que sur 127.0.0.1 ; seul le proxy HTTPS est exposé.
- Jeton de session aléatoire (32 octets), valable 12 h (`api.session-hours`), envoyé en `Authorization: Bearer`, gardé par la page en `sessionStorage` (effacé à la fermeture de l'onglet).
- Anti-force brute : 5 échecs depuis une même IP la bloquent 15 minutes. L'IP vient de `X-Forwarded-For` (dernière valeur) uniquement si la requête arrive de 127.0.0.1, donc du proxy.
- Les routes d'administration refusent toute origine non autorisée.
- Toute action est écrite dans `plugins/AethoriaStats/audit.log` (qui, quoi, cible, résultat, IP de l'admin) et visible dans le panneau (sans l'IP). Les refus et les connexions ratées y figurent aussi.
- `/api/stats` est public et ne contient aucune IP. Dans la console du panneau, les adresses IP, les codes couleur et les mots de passe de `setpass` sont masqués.
- Change les mots de passe uniquement depuis la console du serveur : `/astats setpass` refuse de tourner ailleurs, y compris depuis le panneau.
- Mutes et joueurs immobilisés sont enregistrés dans `plugins/AethoriaStats/moderation.json` et survivent aux redémarrages.

## Les données publiques

Trois fichiers JSON publics, sans IP et sans contenu de chat (on compte seulement les messages) :

- `GET /api/stats` : le résumé léger (environ 20 Ko) : état, TPS, records, classements, activité, morts, monstres, commandes (nom seul), succès, ClearLag.
- `GET /api/players` : les fiches des 500 joueurs les plus actifs (série de jours, heures préférées, rangs...). Chargé seulement quand on ouvre « Joueurs » ou une fiche.
- `GET /api/history` : un point toutes les 5 minutes sur 14 jours (joueurs, TPS moyen et minimal, RAM, chunks, entités). Chargé seulement dans « Serveur » et « Activité ».

Mets `encode zstd gzip` dans le bloc Caddy : l'historique pèse moins de 40 Ko compressé. Les têtes de joueurs viennent de `mc-heads.net` par pseudo (le serveur est en mode hors ligne, l'UUID ne marcherait pas).

Les redémarrages programmés (UltimateAutoRestart) ne sont pas affichés : ce plugin n'expose rien de lisible.

## La page

Barre de menu fixe (Serveur, Joueurs, Activité, Journal, Admin) avec menus déroulants au clavier (flèches, Échap) et bouton « Menu » sur téléphone. Chaque vue a son adresse : `#/serveur`, `#/serveur/performance`, `#/joueurs/classements`, `#/joueur/<pseudo>` (fiche), `#/admin`. Chaque carte a un « ? » qui explique ses chiffres simplement, et une phrase sous chaque graphique.

En haut du script de `site/index.html`, `API_BASE` donne l'adresse de l'API (par défaut `https://141-11-103-141.sslip.io`). Pour un essai local : `?api=http://127.0.0.1:8765`.

Mode démonstration, sans serveur : ouvre la page depuis un petit serveur web local (`python -m http.server` dans `site/`) avec `?demo=1`. Le panneau admin y fonctionne avec un faux serveur : n'importe quel nom et mot de passe passent (nom `modo` pour essayer le rôle modérateur). Pour les captures : `?demo=1&admin=1&tab=players&dlg=1#/admin`, `?demo=1&menu=joueurs`, `?demo=1&help=etat#/serveur`. Les fichiers de démonstration sont `site/stats.sample.json`, `players.sample.json` et `history.sample.json` (régénérés par `plugin/test.sh`).

## Reconstruire et tester

Il faut un JDK 17 ou plus. Dans `plugin/lib/`, mettre `spigot-api.jar` (`spigot-api-1.20.1-R0.1-SNAPSHOT`, dépôt `https://hub.spigotmc.org/nexus/content/repositories/snapshots/`) et `log4j-api-2.19.0.jar`, `log4j-core-2.19.0.jar` (Maven Central). Ces jars ne servent qu'à compiler : le serveur fournit les siens.

```
plugin/build.sh   # crée plugin/build/AethoriaStats.jar (classes en Java 17)
plugin/test.sh    # simulation des stats + test de l'API (auth, rôles, force brute, CORS, console)
```

`java -cp ... fr.aethoria.stats.ApiTest serve 8766 site/stats.sample.json` lance un faux serveur d'API (compte `chef` / `mot-de-passe-solide`) pour essayer la page sans Minecraft.

Ancien envoi vers un Gist GitHub : toujours présent mais désactivé tant que `gist-id` et `github-token` sont vides.
