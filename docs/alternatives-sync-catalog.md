# Options de synchronisation bidirectionnelle legacy ↔ module CATALOG

Ce document liste toutes les options envisageables pour maintenir la cohérence entre les tables legacy (`subscription`, `subscription_version`) et les nouvelles tables `catalog.*` sur SQL Server pendant une migration strangler fig.

**Contraintes dures :**
- Toucher le legacy le moins possible (chaque DDL ou changement de code est un risque).
- La DB legacy est déjà surchargée (toute amplification d'écriture, scan long ou poll fréquent a un coût SLA direct).
- La bascule entre legacy et nouveau doit être réversible sans perte de données.

Les options sont ordonnées **de la plus simple à la plus complexe**, avec un tableau Pour / Contre pour chacune, puis un tableau de synthèse et une recommandation finale.

---

## 1. Polling sur `updated_at`

Le module de sync lit périodiquement `SELECT * FROM subscription WHERE updated_at > @last AND type = 'CATALOG' ORDER BY updated_at, id`. Le curseur `(updated_at, id)` est persisté dans `SyncTracking`.

| Pour | Contre |
|------|--------|
| Aucun DDL si `updated_at` existe déjà | **Ne capture pas les suppressions** (besoin de snapshot de réconciliation) |
| Simplicité conceptuelle, facile à déboguer | Charge de lecture répétée sur la DB legacy |
| Pas de job de capture en arrière-plan | Dépend de la fiabilité de `updated_at` (touché à chaque update ?) |
| Module déjà générique sur `P extends Comparable<P>` | Besoin d'un index sur `(updated_at, id)` — potentiellement un DDL |
| Peut filtrer `type = 'CATALOG'` côté SQL | Risque de collision sur timestamps identiques (mitigé par curseur composite) |

---

## 2. Snapshot périodique + diff

Comparaison complète des lignes CATALOG côté legacy et côté `catalog.*` toutes les N minutes ; écritures correctives basées sur les différences.

| Pour | Contre |
|------|--------|
| Aucun DDL, aucun code legacy modifié | **Requête massive sur la DB legacy** à chaque snapshot — très mauvais ici |
| Robuste : détecte toute dérive, même d'origine inconnue | Latence élevée (la sync n'est plus temps réel) |
| Conceptuellement trivial | Coût croissant avec le volume de données |
| Utile en filet de sécurité | Non viable comme mécanisme principal |

---

## 3. SQL Server Change Tracking (petit frère du CDC)

`ALTER TABLE subscription ENABLE CHANGE_TRACKING`, puis interrogation via `CHANGETABLE(CHANGES subscription, @last_version)`.

| Pour | Contre |
|------|--------|
| **Nettement plus léger que CDC** (pas de job de capture) | DDL sur le legacy (ALTER TABLE) |
| Capture les suppressions nativement | **Ne fournit pas les anciennes valeurs** — jointure nécessaire |
| API SQL standard et documentée | Un seul curseur `@last_version` par table |
| Activable/désactivable sans migration de données | Rétention configurée au niveau base, pas par table |
| Charge faible sur le moteur | Nécessite extension du module (nouveau `CdcSource`) |

---

## 4. CDC classique + colonne `sync_source` (Plan A)

`sp_cdc_enable_db` + `sp_cdc_enable_table` sur les deux tables legacy, et ajout d'une colonne `sync_source VARCHAR(10) DEFAULT 'APP'`. Le module fonctionne tel quel.

| Pour | Contre |
|------|--------|
| **Exactement ce que le module `schema-sync-module` supporte** | DDL sur le legacy (colonne + activation CDC) |
| Temps réel, suppressions capturées | CDC ajoute un capture-job permanent sur la DB surchargée |
| Loop prevention triviale via `event.isSyncOriginated()` | Capture-job lit tout le trafic, pas que CATALOG |
| Pattern éprouvé, tests d'intégration déjà présents | Rétention CDC à tuner (sinon croissance des tables `cdc.*`) |
| Ajout de colonne nullable = metadata-only (SQL Server 2012+) | Nécessite fenêtre de déploiement pour le DDL |

---

## 5. Pattern Outbox dans l'application legacy

Un décorateur de repository, un `EntityListener` JPA, ou un aspect AOP écrit dans `catalog_outbox` dans la même transaction que l'écriture métier.

| Pour | Contre |
|------|--------|
| **Aucun DDL sur les tables legacy** | Modification du code legacy |
| Une seule INSERT supplémentaire par écriture | Nécessite un relais (poller outbox ou CDC sur outbox) |
| Cohérence transactionnelle forte | Si le legacy est mal architecturé, l'injection peut être invasive |
| Pas de scan ni de job de capture | Ne capture pas les écritures hors application (scripts DBA) |
| Filtrage métier précis (uniquement `type = 'CATALOG'`) | Couvre mal les migrations de données massives |

---

## 6. Triggers AFTER sur les tables legacy

Triggers `AFTER INSERT/UPDATE/DELETE` filtrés sur `type = 'CATALOG'`, qui écrivent dans `catalog_outbox`.

| Pour | Contre |
|------|--------|
| Temps réel, capture les suppressions | DDL sur le legacy (triggers) |
| Pas besoin d'activer CDC | **Amplification d'écriture sur chaque transaction legacy** |
| Contrôle fin sur ce qui est capturé | Toute lenteur du trigger ralentit le legacy directement |
| Capture aussi les écritures hors application | Débogage plus difficile (logique cachée dans la DB) |
| Outbox lisible par simple polling | Risque accru de deadlocks si l'outbox est chargée |

---

## 7. Backfill ponctuel au moment du bascule

Pas de sync continue. À chaque flip du flag de la passerelle, on lance un backfill borné sur la fenêtre où l'autre côté était propriétaire.

| Pour | Contre |
|------|--------|
| **Zéro charge en régime établi** | Nécessite tout de même un mécanisme de capture pour le backfill |
| Moins de code à maintenir au quotidien | Bascule n'est plus instantanée (attendre la fin du backfill) |
| Aucune infrastructure permanente | Fenêtre de propriété à tracer précisément |
| Simplicité opérationnelle entre les bascules | Mauvaise UX pour une bascule urgente (bug critique) |
| | Risque d'incohérence si backfill partiel ou échoué |

---

## 8. Interrogation de l'API REST du legacy

Le nouveau module interroge un endpoint `/subscriptions/changes?since=…` exposé par le service legacy.

| Pour | Contre |
|------|--------|
| Aucun changement DB | Modification du code legacy pour exposer l'endpoint |
| Contrôle applicatif complet de ce qui est exposé | Charge sur l'application legacy (et in fine sur la DB) |
| Pas de broker ni CDC à opérer | Nécessite un curseur côté legacy |
| Contrat versionnable et testable facilement | Suppressions difficiles à exposer sans soft-delete |
| | Scalabilité limitée par la capacité de l'app legacy |

---

## 9. CDC sans colonne + log LSN externe (Plan C)

CDC activé sur les tables legacy sans ajout de colonne. Une table `legacy_write_log` (dans un nouveau schéma) enregistre les LSN des écritures sync, plus un cache in-memory des écritures en vol, pour couvrir la race CDC-avant-log.

| Pour | Contre |
|------|--------|
| Aucun DDL sur les tables legacy (seulement activation CDC) | CDC ajoute un capture-job permanent |
| Temps réel, suppressions capturées | Résolution du commit-LSN délicate (`fn_cdc_map_time_to_lsn`) |
| Capture aussi les écritures hors application | **Race window** entre sync-write et insert dans `legacy_write_log` |
| Module sync réutilisé majoritairement | Nécessite un cache in-memory pour couvrir la race |
| | Option la plus complexe à tester rigoureusement |

---

## 10. Événements de domaine via broker (Kafka, RabbitMQ, Service Bus)

L'application legacy publie à chaque écriture métier vers un broker ; le nouveau module consomme.

| Pour | Contre |
|------|--------|
| Découplage fort, architecture évolutive | Modification du code legacy |
| Rejouabilité si broker durable (Kafka) | Nouvelle infrastructure à opérer (broker, DLQ, monitoring) |
| Pas de charge supplémentaire DB | **Problème du dual-write** (DB OK + publish KO = événement perdu) |
| Naturellement bidirectionnel (topics séparés) | Nécessite un outbox transactionnel + relais pour fiabilité |
| Scalable au-delà du périmètre CATALOG | Idempotence côté consommateur obligatoire |

---

## 11. Dual-write à la passerelle avec mirroring asynchrone

La passerelle écrit au propriétaire (legacy ou nouveau selon le flag) et, en asynchrone, miroir vers l'autre côté via une file.

| Pour | Contre |
|------|--------|
| **Aucune modification du legacy** (ni DDL, ni code) | **La passerelle devient data-aware** (comprend le modèle CATALOG) |
| **Aucune charge supplémentaire sur la DB legacy** | Sémantique de livraison : échec de publication = événement perdu |
| Symétrie naturelle entre les deux directions | Nécessite un outbox transactionnel côté passerelle |
| Contrôle total du flux | Idempotence côté consommateur obligatoire |
| | Ne capture pas les écritures directes en DB |

---

## 12. Réplication transactionnelle SQL Server

Configuration d'une publication sur `subscription` + `subscription_version` filtrée `type = 'CATALOG'`, avec souscripteur côté `catalog.*`. Pour le bidirectionnel, deux publications croisées.

| Pour | Contre |
|------|--------|
| Fonctionnalité native SQL Server, mature | Charge similaire au CDC sur la DB legacy |
| Filtrage par ligne et par colonne natif | Configuration complexe (publisher/distributor/subscriber) |
| Réplication vers une base isolée possible | Réplication unidirectionnelle par défaut |
| Rattrapage automatique après panne | Bidirectionnel nécessite gestion de conflits explicite |
| | Mapping N-vers-M difficile (attend des schémas compatibles) |
| | Opérationnellement lourd (administration SQL Server avancée) |

---

## 13. Temporal Tables (tables à versionnement système)

Conversion de `subscription` et `subscription_version` en tables `SYSTEM_VERSIONED`, avec tables d'historique auto-maintenues.

| Pour | Contre |
|------|--------|
| Historique complet automatique | **Changement DDL majeur** (conversion de la table) |
| Requêtes temporelles natives (`FOR SYSTEM_TIME AS OF`) | **Chaque écriture est dupliquée** dans la table d'historique |
| Capture modifications et suppressions avec anciennes/nouvelles valeurs | Migration potentiellement longue et bloquante sur une table volumineuse |
| Pas de job de capture en arrière-plan | Amplification de charge inacceptable sur une DB déjà saturée |
| | Non adapté au cas d'usage ici |

---

## Tableau de synthèse

| # | Option | DDL legacy | Code legacy | Charge DB legacy | Temps réel | Capture deletes | Complexité |
|---|--------|:----------:|:-----------:|:----------------:|:----------:|:---------------:|:----------:|
| 1 | Polling `updated_at` | Index éventuel | Non | Moyenne (SELECTs) | Quasi | **Non** | Très faible |
| 2 | Snapshot + diff | Non | Non | **Très élevée** | Non | Oui | Très faible |
| 3 | Change Tracking | Oui (ALTER) | Non | Faible | Oui (poll) | Oui | Faible |
| 4 | CDC + `sync_source` | Oui (2 ALTER + CDC) | Non | Moyenne-élevée | Oui | Oui | Faible |
| 5 | Outbox applicatif | Non | Oui (léger) | Très faible | Oui | Oui | Moyenne |
| 6 | Triggers + outbox | Oui (triggers) | Non | **Élevée** | Oui | Oui | Moyenne |
| 7 | Backfill au bascule | Dépend | Dépend | Ponctuelle | Non | Dépend | Moyenne |
| 8 | API REST legacy | Non | Oui (endpoint) | Variable | Quasi | Partiel | Moyenne |
| 9 | CDC sans colonne + log LSN | CDC seul | Non | Moyenne-élevée | Oui | Oui | Élevée |
| 10 | Événements via broker | Non | Oui | Très faible | Oui | Oui | Élevée |
| 11 | Dual-write passerelle | **Non** | **Non** | **Nulle** | Oui | Oui | Élevée |
| 12 | Réplication transactionnelle | Configuration | Non | Moyenne | Oui | Oui | Très élevée |
| 13 | Temporal Tables | Oui (majeur) | Non | **Élevée** | Oui | Oui | Très élevée |

---

## Recommandations

En fonction de ce qui est négociable avec les DBA et l'équipe legacy :

- **Si une colonne nullable + CDC sont acceptables** → **option 4** (CDC + `sync_source`). Le module est déjà prêt, c'est le chemin le plus court vers la production.
- **Si on veut du natif DB mais éviter CDC** → **option 3** (Change Tracking). Nettement plus léger, capture les suppressions, une seule ALTER.
- **Si zéro changement sur la DB legacy est impératif mais un changement de code legacy est toléré** → **option 5** (outbox applicatif). Une INSERT par écriture, cohérence transactionnelle, pas de job de capture.
- **Si ni la DB ni le code legacy ne peuvent être touchés** → **option 11** (dual-write passerelle). La passerelle devient data-aware, mais zéro impact sur le legacy.

À **éviter** dans ce contexte (DB surchargée) : options **2** (snapshot), **6** (triggers), **13** (temporal tables) — toutes amplifient la charge sur la DB legacy de façon notable.
