# Schema Sync Module

Librairie Spring Boot pour synchroniser des schémas pendant une migration de type
strangler fig. Le cas cible est une synchronisation bidirectionnelle entre un legacy et un
nouveau module, avec support des mappings N-vers-M entre tables.

Ce README est divisé en deux parties :

1. Architecture de la librairie
2. Guide d'utilisation avec configuration et exemples

---

## 1. Architecture de la librairie

### Problème adressé

Pendant une migration progressive, deux modèles de données peuvent coexister :

- le modèle legacy, encore utilisé par l'ancien module ;
- le modèle du nouveau module, souvent restructuré ;
- une phase de transition où les deux doivent rester cohérents ;
- la possibilité de revenir temporairement vers le legacy sans perte de données.

La librairie fournit le socle technique pour lire les changements via CDC, les transformer, puis
les écrire vers une cible JDBC ou REST.

### Vue d'ensemble

```text
┌────────────────┐        ┌────────────────────┐
│ Service legacy │ ─────▶ │ Tables legacy       │
└────────────────┘        └─────────┬──────────┘
                                     │ CDC
                                     ▼
                            ┌────────────────┐
                            │ Sync service   │
                            │                │
                            │ CdcSource      │
                            │ SyncMapping    │
                            │ SyncSink       │
                            └───────┬────────┘
                                    │ JDBC ou REST
                                    ▼
┌────────────────┐        ┌────────────────────┐
│ Nouveau module │ ◀───── │ Tables nouveau      │
└────────────────┘        └────────────────────┘
```

Le service de synchronisation est conçu pour être déployé indépendamment des applications
métier. Les services legacy et nouveau module n'ont pas besoin d'importer la librairie, sauf si
vous choisissez explicitement de leur faire porter une partie du mécanisme de marquage.

### Flux interne

Un cycle de synchronisation suit toujours la même séquence :

```text
CdcSource
  -> ChangeEvent
  -> SyncMapping.map(...)
  -> List<SyncCommand>
  -> SyncSink
```

- `CdcSource` lit les changements disponibles dans la source CDC.
- `ChangeEvent` représente un changement de ligne : table/capture instance, opération, colonnes,
  position CDC et origine.
- `SyncMapping` contient votre logique de transformation métier.
- `SyncCommand` représente une écriture cible : table ou ressource cible, opération, valeurs,
  clés.
- `SyncSink` applique les commandes, soit par JDBC, soit par REST.

### Abstractions principales

#### `SyncMapping`

Interface que vous implémentez pour chaque direction ou contexte métier.

```java
public interface SyncMapping<P extends Comparable<P>> {
    String name();
    List<String> sourceCaptureInstances();
    List<SyncCommand> map(ChangeEvent<P> event);
    SyncDirection direction();

    default String sinkName() {
        return "default";
    }
}
```

Un mapping peut retourner plusieurs commandes pour un seul changement CDC. C'est le mécanisme
utilisé pour les cas N-vers-M :

```text
1 changement source
  -> écriture table cible A
  -> écriture table cible B
  -> écriture projection cible C
```

#### `SyncCommand`

Commande d'écriture produite par un mapping.

```java
SyncCommand.upsert(
    "dbo.target_table",
    Map.of("id", 1, "status", "ACTIVE"),
    "id"
);

SyncCommand.delete(
    "dbo.target_table",
    Map.of("id", 1),
    "id"
);
```

Pour une cible JDBC, `targetTable` est une table SQL. Pour une cible REST, `targetTable` peut être
interprété par un planner comme une ressource logique.

#### `SyncSink`

Deux sinks sont fournis :

- `JdbcSyncSink` : applique des `MERGE` / `DELETE` via `JdbcTemplate`.
- `RestApiSyncSink` : transforme chaque `SyncCommand` en appel HTTP.

Le sink est choisi par mapping via `sinkName()`.

### Position CDC et tracking

La librairie stocke la position traitée par mapping dans une table de tracking :

```sql
SELECT sync_name, last_sync_time, changes_synced, errors_count
FROM dbo.SyncTracking;
```

Chaque mapping a son propre curseur. Le service peut donc reprendre après un restart sans relire
tout l'historique.

### Prévention des boucles

Sans prévention, une synchronisation bidirectionnelle peut boucler :

```text
legacy -> nouveau -> legacy -> nouveau -> ...
```

Chaque mapping doit donc ignorer les changements produits par la synchronisation :

```java
if (event.isSyncOriginated()) {
    return List.of();
}
```

#### SQL Server : table `dbo.sync_markers`

Pour SQL Server, la librairie évite d'imposer une colonne `sync_source` dans les tables métier.
Le `JdbcSyncSink` insère une ligne dans `dbo.sync_markers` dans la même transaction que les
écritures métier.

CDC attribue alors le même `__$start_lsn` :

```text
cdc.dbo_sync_markers_CT    __$start_lsn = 0xABC
cdc.dbo_business_table_CT  __$start_lsn = 0xABC
```

Le `SqlServerCdcSource` détecte que le changement métier a le même LSN qu'un marker, puis marque
l'événement comme `Origin.SYNC`.

`CONTEXT_INFO` ou `SESSION_CONTEXT` ne suffisent pas seuls, car SQL Server CDC ne les expose pas
dans les lignes CDC. Ils ne deviennent utiles que si une table, un trigger ou un marker persiste
cette information.

#### REST et origine des écritures

Quand la cible est REST, le sync service n'écrit pas directement en base : il appelle une API, et
le service cible fait l'écriture SQL. Si ces tables sont ensuite relues par CDC, le service cible
doit coopérer pour éviter les boucles.

Approches possibles :

- faire écrire le service cible dans `dbo.sync_markers` dans la même transaction que son écriture ;
- envoyer un header interne, par exemple `X-Sync-Origin`, puis marquer la transaction côté cible ;
- utiliser temporairement une colonne `sync_source`, si vous acceptez de polluer les tables métier.

La recommandation est de préférer `dbo.sync_markers` pour ne pas ajouter de colonne aux tables
legacy ou nouveau module.

### Surveillance et réconciliation

La librairie fournit :

- un monitor de lag CDC (`CdcLagMonitor`) pour détecter une position trop ancienne par rapport à
  la rétention CDC ;
- un health indicator Actuator si Actuator est présent ;
- un moteur de réconciliation optionnel via `ReconciliationCheck`.

---

## 2. Utilisation avec configuration et exemples

### 1. Ajouter la dépendance

Dans le service de synchronisation :

```xml
<dependency>
    <groupId>com.yourcompany</groupId>
    <artifactId>schema-sync-module</artifactId>
    <version>1.0.0</version>
</dependency>
```

Ajoutez aussi le driver JDBC de votre base dans l'application consommatrice :

```xml
<dependency>
    <groupId>com.microsoft.sqlserver</groupId>
    <artifactId>mssql-jdbc</artifactId>
    <version>12.8.1.jre11</version>
    <scope>runtime</scope>
</dependency>
```

### 2. Créer l'infrastructure SQL Server

Appliquez le script SQL Server fourni :

```bash
sqlcmd -S localhost -U sa -P 'password' -d YourDb -i sql/setup-sqlserver.sql
```

Le script crée notamment :

- `dbo.SyncTracking`
- `dbo.sync_markers`
- CDC sur `dbo.sync_markers`

Vous devez ensuite activer CDC sur les tables source que vos mappings vont lire :

```sql
EXEC sys.sp_cdc_enable_table
    @source_schema = N'dbo',
    @source_name = N'Orders',
    @role_name = NULL,
    @supports_net_changes = 1;
```

Le nom de capture par défaut sera souvent `dbo_Orders`. C'est ce nom que vous mettez dans
`sourceCaptureInstances()`.

### 3. Configurer le sync service

Exemple `application.yml` pour SQL Server :

```yaml
server:
  port: 8090

spring:
  datasource:
    url: jdbc:sqlserver://localhost:1433;databaseName=SyncDemoDb;encrypt=true;trustServerCertificate=true
    username: sa
    password: Str0ng_demo_password!

schema-sync:
  platform: sqlserver
  enabled: true
  poll-interval-ms: 5000
  admin:
    enabled: true
  reconciliation:
    enabled: true
    cron: "0 0 * * * *"
```

Les endpoints admin sont exposés sous :

```text
/admin/sync/mappings
/admin/sync/mappings/{name}
/admin/sync/mappings/{name}/run
/admin/sync/mappings/{name}/reset
/admin/sync/pause
/admin/sync/resume
```

Protégez ces endpoints avec Spring Security en production.

### 4. Exemple JDBC : legacy vers nouvelle table

Ce mapping lit une table legacy CDC et écrit dans une table cible via JDBC.

```java
@Component
public class LegacyOrdersToNewOrdersMapping implements SyncMapping<ByteArrayPosition> {

    @Override
    public String name() {
        return "legacy_orders_to_new_orders";
    }

    @Override
    public List<String> sourceCaptureInstances() {
        return List.of("dbo_Orders");
    }

    @Override
    public SyncDirection direction() {
        return SyncDirection.LEGACY_TO_NEW;
    }

    @Override
    public List<SyncCommand> map(ChangeEvent<ByteArrayPosition> event) {
        if (event.isSyncOriginated()) {
            return List.of();
        }

        Map<String, Object> row = event.columns();

        if (event.operation() == ChangeEvent.OperationType.DELETE) {
            return List.of(SyncCommand.delete(
                    "dbo.new_orders",
                    Map.of("order_id", row.get("id")),
                    "order_id"));
        }

        return List.of(SyncCommand.upsert(
                "dbo.new_orders",
                Map.of(
                        "order_id", row.get("id"),
                        "customer_name", row.get("customer"),
                        "status", row.get("status")
                ),
                "order_id"));
    }
}
```

Comme le mapping ne surcharge pas `sinkName()`, il utilise le sink JDBC par défaut : `"default"`.

### 5. Exemple JDBC N-vers-M

Un seul changement source peut écrire plusieurs tables cibles.

```java
@Component
public class OrderLineToItemAndTotalsMapping implements SyncMapping<ByteArrayPosition> {

    private final JdbcTemplate jdbc;

    public OrderLineToItemAndTotalsMapping(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "order_line_to_item_and_totals";
    }

    @Override
    public List<String> sourceCaptureInstances() {
        return List.of("dbo_OrderLines");
    }

    @Override
    public SyncDirection direction() {
        return SyncDirection.LEGACY_TO_NEW;
    }

    @Override
    public List<SyncCommand> map(ChangeEvent<ByteArrayPosition> event) {
        if (event.isSyncOriginated()) {
            return List.of();
        }

        Map<String, Object> row = event.columns();
        Object orderId = row.get("order_id");
        Object lineId = row.get("line_id");

        SyncCommand totals = totalsCommand(orderId);

        if (event.operation() == ChangeEvent.OperationType.DELETE) {
            return List.of(
                    SyncCommand.delete(
                            "dbo.new_order_items",
                            Map.of("item_id", lineId),
                            "item_id"),
                    totals);
        }

        return List.of(
                SyncCommand.upsert(
                        "dbo.new_order_items",
                        Map.of(
                                "item_id", lineId,
                                "order_id", orderId,
                                "sku", row.get("sku"),
                                "quantity", row.get("quantity"),
                                "unit_price", row.get("unit_price")
                        ),
                        "item_id"),
                totals);
    }

    private SyncCommand totalsCommand(Object orderId) {
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS item_count,
                       COALESCE(SUM(quantity * unit_price), 0) AS total_amount
                FROM dbo.OrderLines
                WHERE order_id = ?
                """, orderId);

        return SyncCommand.upsert(
                "dbo.new_order_totals",
                Map.of(
                        "order_id", orderId,
                        "item_count", totals.get("item_count"),
                        "total_amount", totals.get("total_amount")
                ),
                "order_id");
    }
}
```

Le `JdbcSyncSink` exécute toutes les commandes retournées par le mapping.

### 6. Exemple REST : legacy vers API du nouveau module

Quand la cible est une API, déclarez un sink REST :

```java
@Configuration
public class RestSyncConfig {

    @Bean
    SyncSink catalogRestSink(@Value("${catalog.base-url}") String baseUrl) {
        RestClient client = RestClient.builder()
                .baseUrl(baseUrl)
                .build();

        return new RestApiSyncSink("catalog-rest", client, command -> switch (command.targetTable()) {
            case "orders" -> new RestCall(HttpMethod.POST, "/api/orders", command.values());
            case "items" -> new RestCall(HttpMethod.POST, "/api/items", command.values());
            default -> throw new IllegalArgumentException("Unsupported REST target: " + command.targetTable());
        });
    }
}
```

Puis routez un mapping vers ce sink :

```java
@Component
public class LegacyOrdersToCatalogApiMapping implements SyncMapping<ByteArrayPosition> {

    @Override
    public String name() {
        return "legacy_orders_to_catalog_api";
    }

    @Override
    public List<String> sourceCaptureInstances() {
        return List.of("dbo_Orders");
    }

    @Override
    public String sinkName() {
        return "catalog-rest";
    }

    @Override
    public SyncDirection direction() {
        return SyncDirection.LEGACY_TO_NEW;
    }

    @Override
    public List<SyncCommand> map(ChangeEvent<ByteArrayPosition> event) {
        if (event.isSyncOriginated()) {
            return List.of();
        }

        Map<String, Object> row = event.columns();
        return List.of(SyncCommand.upsert(
                "orders",
                Map.of(
                        "id", row.get("id"),
                        "customerName", row.get("customer"),
                        "status", row.get("status")
                ),
                "id"));
    }
}
```

Pour REST, il est souvent préférable que l'API cible mette à jour toutes ses tables internes dans
sa propre transaction, plutôt que de faire connaître tout son schéma au sync service.

### 7. Exemple de réconciliation

Une réconciliation détecte les dérives entre deux modèles.

```java
@Component
public class OrdersReconciliationCheck implements ReconciliationCheck {

    private final JdbcTemplate jdbc;

    public OrdersReconciliationCheck(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "orders-reconciliation";
    }

    @Override
    public ReconciliationResult check() {
        Integer driftCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM dbo.legacy_orders l
                FULL JOIN dbo.new_orders n ON n.order_id = l.id
                WHERE n.order_id IS NULL OR l.id IS NULL
                """, Integer.class);

        return driftCount != null && driftCount == 0
                ? ReconciliationResult.pass(name(), "Aucune dérive détectée")
                : ReconciliationResult.fail(
                        name(),
                        "Dérive détectée entre legacy_orders et new_orders",
                        0,
                        driftCount == null ? 0 : driftCount);
    }
}
```

### 8. Observabilité

Vérifier l'état des mappings :

```sql
SELECT
    sync_name,
    last_sync_time,
    changes_synced,
    errors_count,
    CONVERT(VARCHAR(50), last_lsn, 1) AS last_lsn
FROM dbo.SyncTracking
ORDER BY sync_name;
```

Vérifier les capture instances CDC :

```sql
SELECT capture_instance, supports_net_changes
FROM cdc.change_tables
ORDER BY capture_instance;
```

Vérifier les markers de synchronisation :

```sql
SELECT TOP 50 *
FROM dbo.sync_markers
ORDER BY id DESC;
```

### 9. Exemple complet runnable

Un exemple avec trois services est fourni dans :

```text
examples/two-service-demo
```

Il contient :

- `service-a` : API métier A ;
- `service-b` : API métier B ;
- `sync-service` : service indépendant qui importe cette librairie et exécute les mappings ;
- SQL Server avec CDC activé ;
- exemples JDBC, REST et JDBC N-vers-M.

Lancer l'exemple :

```bash
cd examples/two-service-demo
docker compose up --build
```

Consulter la documentation de l'exemple :

```text
examples/two-service-demo/README.md
```

### 10. Checklist d'intégration

- Créer un service de synchronisation indépendant.
- Ajouter la dépendance `schema-sync-module`.
- Configurer `schema-sync.platform: sqlserver`.
- Créer `dbo.SyncTracking` et `dbo.sync_markers`.
- Activer CDC sur `dbo.sync_markers`.
- Activer CDC sur chaque table source.
- Implémenter un `SyncMapping` par direction ou contexte métier.
- Choisir le sink de chaque mapping : JDBC par défaut ou REST nommé.
- Appeler `event.isSyncOriginated()` au début de chaque mapping.
- Ajouter des tests de mapping et une réconciliation pour les tables critiques.
