# Integration marks

These files are intact vendor-provided artwork, downloaded on 2026-10-26 for the
GateRook integration diagram. They are not GateRook artwork and are not covered
by a blanket open-source licence for this directory. The marks identify the
services being connected; they do not indicate sponsorship or endorsement.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `mysql.svg` | [MySQL logo downloads](https://www.mysql.com/about/legal/logos.html), [MySQL logo usage guidelines](https://www.mysql.com/about/legal/trademark.html) | [MySQL's website SVG](https://labs.mysql.com/common/logos/mysql-logo.svg) |
| `postgresql.svg` | [PostgreSQL logo resources](https://wiki.postgresql.org/wiki/Logo) | [Three-colour Slonik SVG](https://wiki.postgresql.org/images/a/a4/PostgreSQL_logo.3colors.svg) |
| `mongodb.svg` | [MongoDB brand resources](https://www.mongodb.com/company/newsroom/brand-resources), [official MongoDB source](https://github.com/mongodb/mongo/blob/master/docs/leaf.svg) | [MongoDB leaf SVG](https://raw.githubusercontent.com/mongodb/mongo/master/docs/leaf.svg) |
| `opensearch.svg` | [OpenSearch trademark policy and brand assets](https://opensearch.org/trademark-brand-policy/) | [Default OpenSearch mark](https://opensearch.org/wp-content/uploads/2025/01/opensearch_mark_default.svg) |
| `redis.svg` | [Redis newsroom brand kit](https://redis.io/company/news/), [official Redis documentation source](https://github.com/redis/docs/blob/main/assets/icons/redis-cube.svg) | [Redis documentation cube SVG](https://raw.githubusercontent.com/redis/docs/main/assets/icons/redis-cube.svg) |

## Asset notes

- **MySQL:** Oracle's original logo is supplied here unmodified, rather than a
  "Powered by" or "Includes" badge that would make a different product claim.
  The published MySQL page distinguishes the original logo from its Conditional
  Use Logos and says use of the original requires a written agreement. This
  source record does not assert that such permission has been granted. The
  website guidelines call for a logo link to `https://www.mysql.com/`.
- **PostgreSQL:** the official wiki supplies this three-colour elephant and
  refers users to the [PostgreSQL trademark policy](https://www.postgresql.org/about/policies/trademarks/).
- **MongoDB:** the evergreen leaf is supplied unmodified from MongoDB's official
  repository. The brand resources allow the standalone mark when the brand is
  clear; display it with the MongoDB service name to identify the integration.
  It remains subject to MongoDB's trademark and brand usage guidelines.
- **OpenSearch:** the official policy specifically describes architectural
  diagrams illustrating integration. Keep GateRook's own identity more
  prominent; preserve the mark and do not imply endorsement.
- **Redis:** the cube is from the official Redis documentation repository.
  The newsroom links its brand kit at `https://brand.redis.io/`, but that host
  returned a certificate hostname mismatch during retrieval, so no certificate
  checks were bypassed. Artwork is subject to Redis's
  [trademark policy](https://redis.io/legal/trademark-policy/) and
  [brand guidelines](https://redis.io/brand-guidelines/), not a claimed CC0
  licence. Redis is a registered trademark of Redis Ltd.

The SVGs retain their original geometry, colours, proportions and metadata.
They contain no scripts, event handlers, embedded foreign content, or remote
asset references. Render them as local images with proportional sizing and
plain service-name labels; do not recolour the brand artwork for themes.

## Connector-card additions (2026-11-11)

Downloaded on 2026-11-11 for the connector-type cards of the Add connection page, under the same terms as above: intact
vendor-provided artwork, not GateRook artwork, not covered by a blanket open-source licence, identifying the services
being connected without indicating sponsorship or endorsement. Most of these vendors restrict logo use in a product
interface; the notes record each condition, and this record does not assert that any permission has been granted.
`neo4j.svg` is the Neo4j database product icon and `influxdb.svg` the square InfluxData "Cubo", chosen over the
horizontal wordmarks because a wordmark is unreadable on a 32 px tile.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `sqlserver.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | [Azure Public Service Icons V25 ZIP](https://arch-center.azureedge.net/icons/Azure_Public_Service_Icons_V25.zip), member `Azure_Public_Service_Icons/Icons/databases/10132-icon-service-SQL-Server.svg` |
| `sqlite.svg` | [SQLite home page](https://sqlite.org/index.html), [SQLite source tree `art/`](https://sqlite.org/src/dir?ci=trunk&name=art) | [SQLite banner SVG](https://sqlite.org/images/sqlite370_banner.svg) (byte-identical to [`art/sqlite370.svg`](https://sqlite.org/src/doc/trunk/art/sqlite370.svg)) |
| `snowflake.svg` | [Snowflake brand guidelines](https://www.snowflake.com/brand-guidelines/) | [Snowflake logo package ZIP](https://www.snowflake.com/wp-content/uploads/2021/04/Snowflake-Logo-Package.zip), member `Snowflake Logo/Digital/SVG/snowflake-bug-color-rgb.svg` |
| `bigquery.svg` | [Google Cloud icon library](https://cloud.google.com/icons), [product icons overview (PDF)](https://services.google.com/fh/files/misc/google-cloud-product-icons.pdf) | [Core product icons ZIP](https://services.google.com/fh/files/misc/core-products-icons.zip), member `Unique Icons/BigQuery/SVG/BigQuery-512-color.svg` |
| `redshift.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | [AWS icon package 2026-07-31 ZIP](https://d1.awsstatic.com/onedam/marketing-channels/website/public/shared/architecture-icon-release/Icon-package_07312026.5846e92413caa21490223536cc97f1269e44fa92.zip), member `Architecture-Service-Icons_07312026/Arch_Analytics/32/Arch_Amazon-Redshift_32.svg` |
| `databricks.svg` | [Databricks press kit](https://www.databricks.com/company/newsroom/press-kit), [Brandfolder press kit](https://brandfolder.com/databricks/press-kit), [brand guidelines](https://brand.databricks.com/) | [Stacked lockup, full colour SVG](https://cdn.bfldr.com/9AYANS2F/at/8xggx72tfj6krxxq7q7mfv26/stacked-lockup-full-color-rgb.svg) |
| `duckdb.svg` | [DuckDB design and logos](https://duckdb.org/design/), [DuckDB trademark guidelines](https://duckdb.org/trademark_guidelines) | [DuckDB logo package ZIP](https://duckdb.org/design/duckdb.zip), member `duckdb/DuckDB_icon-lightmode.svg` |
| `trino.svg` | [Trino Software Foundation: ownership and trademarks](https://trino.io/foundation), [Trino presentations source](https://github.com/trinodb/presentations/tree/main/assets/logos/icon) | [Trino colour icon SVG](https://raw.githubusercontent.com/trinodb/presentations/main/assets/logos/icon/trino-icon.svg) |
| `athena.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | AWS icon package ZIP (as for Redshift), member `Architecture-Service-Icons_07312026/Arch_Analytics/32/Arch_Amazon-Athena_32.svg` |
| `clickhouse.svg` | [ClickHouse site logo menu ("Download logomark")](https://clickhouse.com/), [ClickHouse trademark policy](https://clickhouse.com/legal/trademark-policy) | [ClickHouse logomark ZIP](https://clickhouse.com/static/brand-assets/clickhouse-logomark.zip), member `clickhouse-logomark/clickhouse-logomark-black.svg` |
| `dynamodb.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | AWS icon package ZIP (as for Redshift), member `Architecture-Service-Icons_07312026/Arch_Databases/32/Arch_Amazon-DynamoDB_32.svg` |
| `firestore.svg` | [Google Cloud icon library](https://cloud.google.com/icons), [product icons overview (PDF)](https://services.google.com/fh/files/misc/google-cloud-product-icons.pdf) | [Category icons ZIP](https://services.google.com/fh/files/misc/category-icons.zip), member `Category Icons/Databases/SVG/Databases-512-color.svg` |
| `firebase-realtime-database.svg` | [Firebase brand guidelines](https://firebase.google.com/brand-guidelines) | [Firebase brand assets ZIP](https://firebase.google.com/static/downloads/brand-guidelines/firebase-brand-assets.zip), member `firebase-brand-assets/Firebase Logo & Product Icons  2/Firebase Product Icons/Product Logomark/SVG/Firebase Realtime Database_Standalone logomark.svg` (two spaces before `2`) |
| `cosmosdb.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | Azure icons ZIP (as for SQL Server), member `Azure_Public_Service_Icons/Icons/databases/10121-icon-service-Azure-Cosmos-DB.svg` |
| `cassandra.svg` | [Apache project logos](https://www.apache.org/logos/), [ASF project-logos sources](https://svn.apache.org/repos/asf/comdev/project-logos/) | [Cassandra eye original SVG](https://svn.apache.org/repos/asf/comdev/project-logos/originals/cassandra-4.svg) |
| `neo4j.svg` | [neo4j.com site logo](https://neo4j.com/), [Neo4j Trademark Policy and Guidelines (PDF)](https://neo4j.pactsafe.io/versions/66ec8074bb19149574eb6cf5.pdf) linked from [Neo4j legal terms](https://legal.neo4j.com/#trademark-policy-and-guidelines) | [Neo4j DB icon SVG](https://dist.neo4j.com/wp-content/uploads/20260408083552/Neo4j_db.svg) (Neo4j database product icon used on neo4j.com) |
| `influxdb.svg` | [InfluxData branding: product downloads](https://influxdata.github.io/branding/logo/brands/), [InfluxData trademark guidelines](https://www.influxdata.com/legal/guidelines-for-using-influxdata-trademarks/) | [Navy Cubo SVG](https://influxdata.github.io/branding/img/downloads/cubo-navy.svg) (InfluxData "Cubo" symbol (requires special permission)) |
| `prometheus.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [Prometheus colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/prometheus/icon/color/prometheus-icon-color.svg) |
| `elasticsearch.svg` | [Elasticsearch product page](https://www.elastic.co/elasticsearch), [Elastic trademark guidelines](https://www.elastic.co/legal/trademarks) | [Elasticsearch colour logo SVG](https://static-www.elastic.co/v3/assets/bltefdd0b53724fa2ce/blt850b5bd506c6b3ce/5d0cfe28d8ff351753cbf2ad/logo-elastic-search-color-64.svg) |
| `cloud-logging.svg` | [Google Cloud icon library](https://cloud.google.com/icons), [product icons overview (PDF)](https://services.google.com/fh/files/misc/google-cloud-product-icons.pdf) | Category icons ZIP (as for Firestore), member `Category Icons/Observability/SVG/Observability-512-color.svg` |
| `cloudwatch.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | AWS icon package ZIP (as for Redshift), member `Architecture-Service-Icons_07312026/Arch_Management-Tools/32/Arch_Amazon-CloudWatch_32.svg` |
| `loki.svg` | [Grafana Labs Loki data source repository](https://github.com/grafana/grafana-loki-datasource), [Grafana Labs trademark policy](https://grafana.com/trademark-policy/) | [Loki logo SVG](https://raw.githubusercontent.com/grafana/grafana-loki-datasource/main/src/img/loki_icon.svg) |
| `kubernetes.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [Kubernetes colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/kubernetes/icon/color/kubernetes-icon-color.svg) |
| `aws.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | AWS icon package ZIP (as for Redshift), member `Architecture-Group-Icons_07312026/AWS-Cloud-logo_32.svg` |
| `mcp.svg` | [Model Context Protocol specification and documentation repository](https://github.com/modelcontextprotocol/modelcontextprotocol), [modelcontextprotocol.io](https://modelcontextprotocol.io/) | [MCP site favicon SVG](https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/main/docs/favicon.svg) |

### Packages

Each package was downloaded once; only the listed members were extracted, by
exact name, with path-traversal and symlink checks. Packages whose marks are not used here are not listed.

| Package | Bytes | SHA-256 |
|---|---:|---|
| [AWS icon package 2026-07-31](https://d1.awsstatic.com/onedam/marketing-channels/website/public/shared/architecture-icon-release/Icon-package_07312026.5846e92413caa21490223536cc97f1269e44fa92.zip) | 13988918 | `d2d166c453526471749d520e0db022c459abef759d2946cf2dd1d1c992dc6526` |
| [AWS PowerPoint toolkits 2026-07-31](https://d1.awsstatic.com/onedam/marketing-channels/website/public/shared/architecture-icon-release/Microsoft-PPTx-toolkits_07312026.1c286c4a809a3cf2902c88ff63bf7dd1fa3cd55d.zip) (read for the usage guidelines only) | 18900782 | `49b19b5cc99e813453be67fc2389a8abce115d7a3261c75698e62cefe4918221` |
| [Azure Public Service Icons V25](https://arch-center.azureedge.net/icons/Azure_Public_Service_Icons_V25.zip) | 1045239 | `f293d99252b3638557dd08ccbff9f2529083473d39b373fb2af29a7200351339` |
| [Google Cloud core product icons](https://services.google.com/fh/files/misc/core-products-icons.zip) | 318678 | `6531a10f58bc599c24d9a455d81dd757c1a03c3c43da9cddf639b859c1c1eece` |
| [Google Cloud category icons](https://services.google.com/fh/files/misc/category-icons.zip) | 795968 | `e5bc3abd3527dc2500e9bff7f15870783e2c764129c49b7cd4c1b4e105345002` |
| [Firebase brand assets](https://firebase.google.com/static/downloads/brand-guidelines/firebase-brand-assets.zip) | 6218601 | `ac17065f4790742b247743e7fa1d019afbb1e1da6d9950fc73cd40e9cbbb1235` |
| [Snowflake logo package](https://www.snowflake.com/wp-content/uploads/2021/04/Snowflake-Logo-Package.zip) | 13294013 | `95bd008419d3b82a87f7474c82c85b60ea92e1a17387888b7d0266f30bdc03c4` |
| [DuckDB logo package](https://duckdb.org/design/duckdb.zip) | 257540 | `f3326bae5aafd3f8fb7314b229880916557dbe326576cd164b1ad76936f2a4e8` |
| [ClickHouse logomark](https://clickhouse.com/static/brand-assets/clickhouse-logomark.zip) | 4163 | `66989429b9bc8a09d0a0ce08b656e56aadb8d3447537798e2483d1517402ac27` |

### Asset notes

- **SQL Server and Azure Cosmos DB:** Azure Architecture Icons V25 (icon update
  of November 2026). Microsoft permits these icons in architectural diagrams,
  training materials or documentation and reserves every other use for its
  explicit permission. Its rules: show the full service name near the icon;
  do not crop, flip, rotate, distort or reshape it; do not use Microsoft
  product icons to represent your own product. The FAQ bundled in the package
  adds that an icon may only represent the Microsoft product it was designed
  for. The [Microsoft Trademark and Brand Guidelines](https://www.microsoft.com/en-us/legal/intellectualproperty/trademarks)
  say many uses of logos and product icons need a licence first and forbid
  implying affiliation or endorsement. Microsoft publishes no standalone SQL
  Server product logo for third parties; `sqlserver.svg` is the Azure "SQL
  Server" icon, with the Azure SQL and Arc-enabled SQL Server icons as
  alternates. The Azure page places its download link under an "I agree to
  the above terms" checkbox; the package was fetched from that direct link.
- **SQLite:** the feather-and-wordmark banner, identical to `art/sqlite370.svg`
  in the SQLite source tree; it includes a white panel behind the wordmark.
  Icon-only versions exist only as GIF (`art/icon-80x90.gif`,
  `art/icon-243x273.gif`). SQLite publishes no logo-usage policy; asked in the
  [SQLite forum](https://sqlite.org/forum/forumpost/8b24aa8189), D. Richard
  Hipp replied that the logo is a registered trademark and that permitted use
  depends on the use.
- **Snowflake:** the snowflake "bug" from the official logo package. The
  guidelines require the bug always to carry the registration mark, which
  this file includes; clear space equals the logotype x-height and the bug's
  minimum size is 20 px. Partner logos must be centre-aligned and optically
  equal in size. The page grants no general licence; Snowflake's partner and
  marketplace terms tie logo use to these guidelines and to agreements. The
  wordmark logo is `Snowflake Logo/Digital/SVG/snowflake-logo-color-rgb.svg`
  in the same package.
- **BigQuery, Firestore and Cloud Logging:** Google Cloud's icon library,
  2025 icon system (overview updated May 2026). Google offers the icons for
  diagrams, technical documentation and similar material and states no other
  licence. BigQuery has its own core-product icon. Firestore and Cloud Logging
  do not: the overview assigns Firestore the shared Databases category icon
  and Cloud Logging the shared Observability category icon, and says the
  legacy console icons should no longer be used in 2026, so the legacy
  `firestore.svg` and `cloud_logging.svg` were not taken. Google brand
  features otherwise require approval through Google's
  [brand permission terms](https://about.google/brand-resource-center/brand-terms/).
- **Amazon Redshift, Amazon Athena, Amazon DynamoDB, Amazon CloudWatch and
  AWS:** AWS Architecture Icons, release 2026-07-31; the 32 px variants were
  chosen for the card tile (16, 48 and 64 px variants are in the same
  package). AWS lets customers and partners build architecture diagrams with
  the icons and use them in whitepapers, presentations, data sheets, posters
  and other technical material. The guideline deck asks to keep the predefined
  size, colour and format in diagrams, not to crop, flip, rotate or reshape
  icons, and always to name the service. `aws.svg` is the "AWS Cloud" group
  icon carrying the AWS logo; the deck says to use the plain cloud group icon
  (kept as an alternate) in Regions where AWS does not own the rights to the
  "AWS" logo, such as China. The [AWS Trademark Guidelines](https://aws.amazon.com/trademark-guidelines/)
  (updated 2026-11-05) list the AWS smile logo as an AWS Mark, limit fair use
  to plain text without logos and require prior written permission for other
  uses.
- **Databricks:** from the official press kit on Brandfolder, linked from the
  Databricks newsroom. The guidelines require the complete lockup (symbol with
  logotype), allow the vertical lockup where a layout needs it, and forbid
  editing, recolouring, distorting or reconfiguring the logo; no symbol-only
  file is offered. The [brand terms](https://brandguides.brandfolder.com/databricks-extended-brand-guidelines/terms-and-conditions)
  license use only after Databricks approves a permission request; contact
  legal@databricks.com. The CDN serves attachments by key; the key was read
  from the asset's attachment list and confirmed by size and colours
  (`#FF3621`, `#0B2026`). White variants for dark backgrounds are in the same
  asset.
- **DuckDB:** the brand page asks for the supplied files only, unmodified,
  without recolouring, reshaping or combining. The trademark guidelines
  require written approval for any use of DuckDB marks in dashboards, menus,
  marketplace listings or developer consoles, forbid placements that suggest
  partnership, and allow plain-text compatibility references; contact
  quack@duckdb.org. `duckdb/DuckDB_icon-darkmode.svg` is the dark-background
  version.
- **Trino:** the Trino Software Foundation owns the marks; use by others is
  not permitted unless the foundation approves it case by case (contact
  foundation@trino.io). The foundation page refers to the presentations
  source for logo variants. The icon (Commander Bun Bun, pink `#DD00A1`) was
  last changed in commit `a51d989af9da6f7cb53834851ff2e4c28fe3cb50`.
- **ClickHouse:** the brand ZIP offered by the site's own logo menu. The
  trademark policy lets others use the word mark, but not the logo, to
  describe compatibility; logo use needs licence terms from
  trademarks@clickhouse.com. Only scaling is allowed, and black-and-white
  reproduction when the context requires it. The ZIP has black and white
  versions only (`clickhouse-logomark-white.svg` for dark backgrounds).
- **Firebase Realtime Database:** the realtime-database product logomark from
  the official Firebase kit, chosen because Google provides a product icon;
  the plain Firebase logomark is an alternate. The guidelines forbid using the
  Firebase mark together with your own product's name or as the most
  prominent element, allow product icons on dark or neutral backgrounds where
  Firebase is clear from context, ask for no more than one Firebase logo in
  one place (stated for product lockups), set a 24 px minimum height and
  forbid using the standard lockup in a product. The Firestore product
  logomark (alternate) also contains the Firebase flame, so showing it next to
  `firebase-realtime-database.svg` would place two Firebase marks together.
- **Apache Cassandra:** the eye-only original from the ASF project-logos
  sources that generate www.apache.org/logos (last changed in r1890541,
  2021-06-07; its metadata names the Cassandra project's own logo sheet as the
  source). The published renders on www.apache.org/logos are PNG and PDF only
  and all include the "Apache Cassandra" wordmark. The SVG has no viewBox
  (269.18 × 142.40); WebKit and Chromium 156 scale it correctly as an image.
  The [ASF trademark policy](https://www.apache.org/foundation/marks/) allows
  project graphic logos without asking only on your website as a hyperlink to
  the project or apache.org; all other uses need written approval from the
  VP, Brand Management, the Brand Management Committee or the project's VP.
  The Apache License covering the artwork's copyright grants no trademark
  rights. Not used: the Cassandra website repository's `site-ui/src/img/logo.svg`
  (a Couchbase sample logo), its `logo-white.svg` and originals 1, 2, 3, 6
  and 9 (embedded PNG).
- **Neo4j:** the logo served from Neo4j's CDN and used on neo4j.com
  (neo4j.com/brand redirects to the Needle design system, which offered no
  static download). The trademark policy states that third parties may not
  use Neo4j trademarks without a written agreement or express permission,
  allows only proportional scaling, requires the exact Style Guide colours and
  a trademark notice. Horizontal wordmark with dark text; the Neo4j database
  product icon from neo4j.com is an alternate.
- **InfluxDB:** the trademark guidelines forbid any InfluxData logo, including
  the cuboctahedron, in connection with products, services or websites without
  an express written logo licence; fair use excludes logo reproduction;
  requests go through InfluxData's permission form and take 4 to 6 weeks.
  The branding docs mark the square "Cubo" symbol (alternate) as needing
  special permission. Navy `#020A47` only.
- **Prometheus and Kubernetes:** CNCF artwork, subject to the Linux Foundation
  trademark usage guidelines quoted in the repository's `LICENSE.md`.
  Kubernetes® is listed as a registered trademark of LF Projects, LLC. Logos
  may link to the project home page and support accurate compatibility
  statements without implied endorsement; showing them on websites or
  marketing that promote your products needs written permission; colours
  must not change. Certified Kubernetes marks are separate and only for
  conformant products. Questions: info@cncf.io. Last changes: Kubernetes
  `40e2e8948509b40e4bad479446aaec18d6273bf2`, Prometheus
  `c33a8386bce4eabc36e1d4972e0996db4630037b`.
- **Elasticsearch:** the colour product logo used on elastic.co. Elastic's
  CDN always sends this file with gzip content encoding, even when an
  uncompressed response is requested; the stored file is the decompressed SVG
  (the 699-byte gzip body has SHA-256
  `5ff70d221f4ea2598b5a22598c28cb8f6aed1f94ddd955654c4e037dbc2b9f40`). elastic.co/brand
  redirects to the trademark guidelines, which reserve the logo designs for
  authorised resellers, distributors and licensees with written licences;
  others may use the word mark ("for Elasticsearch", "compatible with
  Elasticsearch") only for products that fully work with it, less prominently
  than their own name and without implying affiliation. EUI's
  `logo_elasticsearch.svg` was not used: its middle bar has no fill outside
  EUI's CSS and disappears when rendered alone.
- **Grafana Loki:** from Grafana Labs' Loki data source repository; the logo
  dates from commit `9ae21b314f805521857cbaa4be7ad4e5f4be94e5` ("loki: updates
  the logo", 2018-12-12) and uses `#F9EC1C` to `#F05A2B` gradients. The
  grafana/loki repository has PNG logos only. The trademark policy allows the
  marks for open-source discussion, development and support; any other use
  requires a separate trademark licence, and commercial or website use needs
  express written permission (hello@grafana.com).
- **Model Context Protocol:** from the official specification and
  documentation repository, whose site configuration declares
  `docs/favicon.svg` as the favicon and `docs/logo/light.svg` and `dark.svg`
  as the logo. MCP is a Series of LF Projects, LLC; Model Context Protocol®
  and MCP™ are listed LF Projects marks, so the LF Projects
  [trademark policy](https://lfprojects.org/policies/trademark-policy/) applies: a logo may link to the project home
  page; showing it on websites or marketing that promote your products needs written permission. `mcp.svg` is the white symbol on a black rounded
  square, last changed in commit `3b05e1498d027d03c79358dcd22d3d5620ba341d`.
  The repository's copyright licences grant no trademark rights.
- **Neo4j (`neo4j.svg`):** the product icon is from the same CDN as the full-colour logo; the same policy applies.
- **InfluxDB (`influxdb.svg`):** InfluxData's branding docs mark the "Cubo" as needing special permission, in
  addition to the logo licence above.

### Not included

- **Oracle Database:** not in this collection: oracle.com refused the automated request and Oracle publishes no
  Oracle Database product icon. The Oracle mark was later taken from Oracle's own GitHub repository; see the
  compatible-service additions below, which also record Oracle's written-authorisation requirement.
- **Google Cloud:** the press kit offers only the "Google Cloud" wordmark lockup, unreadable on a 32 px tile, and
  no symbol-only file. The GCP card uses Google Cloud's site icon instead; see the cloud connector additions below.

These SVGs also retain their original geometry, colours, proportions and metadata, with no scripts, event handlers,
embedded foreign content, embedded raster images or remote asset references. Cards show each one with the service's
plain name on a light plate in every theme, never recoloured.

## Cloud connector additions (2026-11-11)

Added on 2026-11-11 for the cloud connector types of the Add connection page, under the same terms as above: intact
vendor-provided artwork, not GateRook artwork, not covered by a blanket open-source licence, identifying the services
being connected without indicating sponsorship or endorsement. This record does not assert that any permission has
been granted. The owner approved both downloads below and the use of Google Cloud's site icon on 2026-11-11.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `azure.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | Azure icons ZIP (as for SQL Server), member `Azure_Public_Service_Icons/Icons/other/10018-icon-service-Azure-A.svg` |
| `azure-monitor.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | Azure icons ZIP (as for SQL Server), member `Azure_Public_Service_Icons/Icons/monitor/00001-icon-service-Monitor.svg` |
| `s3.svg` | [AWS Architecture Icons](https://aws.amazon.com/architecture/icons/) | AWS icon package ZIP (as for Redshift), member `Architecture-Service-Icons_07312026/Arch_Storage/32/Arch_Amazon-Simple-Storage-Service_32.svg` |
| `cloudflare.png` | [Cloudflare press kit](https://www.cloudflare.com/press-kit/) ("Company logos"), [Cloudflare trademark guidelines](https://www.cloudflare.com/trademark/) | [Cloudflare logos ZIP](https://cf-assets.www.cloudflare.com/dzlvafdwdttg/2Twekn3xyYyd94qDYAl0ed/9ab649caa40958f195166e0d9f5d9a04/Logos.zip), member `CF-Logo 1.png` |
| `google-cloud.png` | [cloud.google.com](https://cloud.google.com/), whose page head names this file as its `apple-touch-icon` | [Google Cloud site icon PNG](https://www.gstatic.com/cgc/super_cloud_gradient.png) |

### Downloads

The Azure and AWS members were extracted from the packages listed above, which were not downloaded again. The
Cloudflare member was extracted by exact name; the archive holds no links, and its only other entries are the second
image and macOS resource forks under `__MACOSX/`, none of them extracted into the kit.

| Download | Bytes | SHA-256 |
|---|---:|---|
| [Cloudflare logos ZIP](https://cf-assets.www.cloudflare.com/dzlvafdwdttg/2Twekn3xyYyd94qDYAl0ed/9ab649caa40958f195166e0d9f5d9a04/Logos.zip) | 78319 | `00420d13d6541742116e2de5f0fd496a0b6532d9dc00d43c55867c84b43e56a5` |
| [Google Cloud site icon PNG](https://www.gstatic.com/cgc/super_cloud_gradient.png) | 9447 | `a332b54b620a2fa04413561c01c925cd40f936581c7bed52df424ecccc9c08b4` |

### Asset notes

- **Azure and Azure Monitor:** Azure Architecture Icons V25, under the Microsoft terms recorded above for SQL Server
  and Azure Cosmos DB: diagrams, training material and documentation, with every other use reserved for Microsoft's
  explicit permission; the full service name next to the icon; no cropping or reshaping; an icon represents only the
  Microsoft product it was designed for. `azure.svg` is the Azure "A" from the package's `other` folder and identifies
  Azure on the Azure card; `azure-monitor.svg` is the Monitor service icon and identifies Azure Monitor Logs.
- **Amazon S3:** AWS Architecture Icons, release 2026-07-31, 32 px variant, under the AWS terms recorded above for
  Amazon Redshift. The S3 card also reaches other storage that speaks the S3 API; the icon identifies Amazon S3, whose
  API the connector uses, next to the card's plain name.
- **Cloudflare:** the press kit's only logo download ("Logo on transparent and white background") holds two raster
  files of the full lockup, the orange cloud above the CLOUDFLARE wordmark. `CF-Logo 1.png` (512 x 173, transparent)
  was taken; `CF Logo 2.jpg` (1277 x 431, white background) was not. No SVG and no symbol-only file is offered. The
  trademark guidelines require Cloudflare's written permission for any use of its logos other than its Web Badges,
  forbid modifying the marks or combining them with other symbols, words or images, and forbid implying affiliation,
  endorsement or sponsorship; plain-text references such as "for use with Cloudflare" need no permission. On a 32 px
  tile the cloud identifies the service and the wordmark is too small to read.
- **Google Cloud:** Google Cloud's asset collections offer no symbol-only mark (see Not included above). With the
  owner's agreement, the GCP card uses the icon that cloud.google.com itself declares as its `apple-touch-icon`: the
  gradient cloud symbol, a 192 x 192 PNG with transparency on Google's static host. Its use remains subject to
  Google's [brand permission terms](https://about.google/brand-resource-center/brand-terms/), as for BigQuery above.

The PNG files are the downloaded bytes, unmodified; the SVGs keep their original geometry, colours, proportions and
metadata, with no scripts, event handlers, embedded foreign content, embedded raster images or remote asset
references. Cards show each one with the service's plain name on a light plate in every theme, never recoloured.

## Compatible-service additions (2026-11-11)

Downloaded on 2026-11-11 for the cards of services that speak a built-in connector's protocol (Supabase, Valkey, Amazon
Aurora and RDS, Oracle Autonomous Database and others) on the Add connection page, under the same terms as above:
intact vendor-provided artwork, not GateRook artwork, not covered by a blanket open-source licence, identifying the
services being connected without indicating sponsorship or endorsement. Most of these vendors restrict logo use in a
product interface; the notes record each condition, and this record does not assert that any permission has been
granted. `oracle.svg` also gives the Oracle Database card its mark. `mariadb.svg`, `cockroachdb.svg` and `scylladb.svg`
come from the connector-card collection of the same day. Cards whose vendor publishes no usable mark keep the base
connector's icon.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `aws-rds.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Databases/32/Arch_Amazon-RDS_32.svg` |
| `aws-documentdb.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Databases/32/Arch_Amazon-DocumentDB_32.svg` |
| `aws-elasticache.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Databases/32/Arch_Amazon-ElastiCache_32.svg` |
| `aws-keyspaces.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Databases/32/Arch_Amazon-Keyspaces_32.svg` |
| `aws-opensearch.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Analytics/32/Arch_Amazon-OpenSearch-Service_32.svg` |
| `aws-eks.svg` | as above | AWS icon package ZIP, member `Architecture-Service-Icons_07312026/Arch_Containers/32/Arch_Amazon-Elastic-Kubernetes-Service_32.svg` |
| `azure-postgresql.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | [Azure Public Service Icons V25 ZIP](https://arch-center.azureedge.net/icons/Azure_Public_Service_Icons_V25.zip) (copy from the earlier collection), member `Azure_Public_Service_Icons/Icons/databases/10131-icon-service-Azure-Database-PostgreSQL-Server.svg` |
| `azure-mysql.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/databases/10122-icon-service-Azure-Database-MySQL-Server.svg` |
| `azure-cache-redis.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/databases/10137-icon-service-Cache-Redis.svg` |
| `azure-sql-mi.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/databases/10136-icon-service-SQL-Managed-Instance.svg` |
| `azure-cassandra.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/other/02663-icon-service-Managed-Instance-Apache-Cassandra.svg` |
| `azure-aks.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/containers/10023-icon-service-Kubernetes-Services.svg` (the `compute/` copy is byte-identical) |
| `azure-databricks.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/analytics/10787-icon-service-Azure-Databricks.svg` |
| `azure-sql.svg` | as above | Azure icons ZIP, member `Azure_Public_Service_Icons/Icons/databases/02390-icon-service-Azure-SQL.svg` (byte-identical to the earlier alternate `../marks/alternates/sqlserver-azure-sql.svg`) |
| `gcp-cloud-sql.svg` | [Google Cloud icon library](https://cloud.google.com/icons), [product icons overview (PDF, May 2026)](https://services.google.com/fh/files/misc/google-cloud-product-icons.pdf) | [Core product icons ZIP](https://services.google.com/fh/files/misc/core-products-icons.zip) (copy from the earlier collection), member `Unique Icons/Cloud SQL/SVG/CloudSQL-512-color.svg` |
| `gcp-alloydb.svg` | as above | Core product icons ZIP, member `Unique Icons/AlloyDB/SVG/AlloyDB-512-color.svg` |
| `gcp-gke.svg` | as above | Core product icons ZIP, member `Unique Icons/GKE/SVG/GKE-512-color.svg` |
| `gcp-memorystore.svg` | as above | [Category icons ZIP](https://services.google.com/fh/files/misc/category-icons.zip) (copy from the earlier collection), member `Category Icons/Databases/SVG/Databases-512-color.svg` |
| `oracle.svg` | [Oracle MCP servers repository, Oracle's GitHub organisation](https://github.com/oracle/mcp), [Oracle logo guidelines](https://www.oracle.com/legal/logos/) | [Oracle "O" SVG](https://raw.githubusercontent.com/oracle/mcp/main/oracle.svg) |
| `supabase.svg` | [Supabase brand guidelines](https://supabase.com/brand) | [Supabase icon SVG](https://supabase.com/brand-assets/supabase-icon-green-transparent.svg) |
| `neon.svg` | [Neon brand guidelines](https://neon.com/brand) | [Neon logomark for light backgrounds](https://neon.com/brand/neon-logomark-light-color.svg?updated=2026-06-03) |
| `timescale.svg` | [Tiger Data brand](https://www.tigerdata.com/brand), [Tiger Data trademarks](https://www.tigerdata.com/legal/trademarks), [TimescaleDB repository](https://github.com/timescale/timescaledb) | [Tiger Data badge, black SVG](https://assets.tigerdata.com/timescale-web/brand/tiger-data/flat-logos/download/badge-black.svg) |
| `postgis.svg` | [PostGIS website](https://postgis.net/) | [Site brand image](https://postgis.net/brand.svg) (byte-identical to [the site's SVG favicon](https://postgis.net/favicon/favicon.svg)) |
| `yugabytedb.svg` | [Yugabyte brand logos](https://www.yugabyte.com/yugabyte-brand-logos/) | [YugabyteDB logo 2023 ZIP](https://www.yugabyte.com/wp-content/uploads/2023/04/yugabytedb-logo-2023.zip), member `Logo Assets 2/Logomark/RGB/Color/SVG/Yugabyte-Logomark-RGB.svg` |
| `edb.png` | [EDB documentation site](https://www.enterprisedb.com/docs/) (declares it as `apple-touch-icon` 512 x 512), [EDB media resources](https://www.enterprisedb.com/press-room/media-resources), [EDB trademarks](https://www.enterprisedb.com/trademarks) | [Docs site icon PNG](https://www.enterprisedb.com/docs/icons/icon-512x512.png?v=5e297e8d230454ea0728660cf024982c) |
| `planetscale.svg` | [PlanetScale brand assets](https://planetscale.com/brand), [PlanetScale site terms](https://planetscale.com/legal/siteterms) | [Logo mark, black SVG](https://planetscale.com/brand/planetscale-logo-mark-black.svg) |
| `vitess.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [Vitess colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/vitess/icon/color/vitess-icon-color.svg) |
| `tidb.svg` | [TiDB brand guidelines](https://www.pingcap.com/tidb-brand-guidelines/) | [TiDB logo and symbol ZIP](https://static.pingcap.com/files/2024/09/26051203/TiDB-Logo-Symbol.zip), member `TiDB Logo + Symbol/2. Symbol/SVG/TiDB-Symbol-Red-RGB.svg` |
| `singlestore.png` | [SingleStore visual identity guidelines](https://www.singlestore.com/brand/) (its pages declare the icon as `apple-touch-icon` 512 x 512), [SingleStore terms](https://www.singlestore.com/terms/) | [Site icon PNG](https://www.singlestore.com/icons/icon-512x512.png?v=277b9cbbe31e8bc416504cf3b902d430) |
| `percona.svg` | [Percona brand resources](https://www.percona.com/brand-resources/), [Percona trademark policy](https://www.percona.com/trademark-policy/) | "Percona Logomark Only - Full Colour" SVG, the [Google Drive file](https://drive.google.com/file/d/1JYquU2BJuk31aU20Z7BJ4W_IqfbyKeDi/view?usp=sharing) linked from the brand page, fetched as `https://drive.google.com/uc?export=download&id=1JYquU2BJuk31aU20Z7BJ4W_IqfbyKeDi` (served from `drive.usercontent.google.com`) |
| `oceanbase.svg` | [OceanBase Design, OceanBase's design system in its GitHub organisation](https://github.com/oceanbase/oceanbase-design) | [OceanBase symbol SVG](https://raw.githubusercontent.com/oceanbase/oceanbase-design/master/public/assets/oceanbase_logo.svg) |
| `polardb.png` | [PolarDB for PostgreSQL documentation repository, polardb GitHub organisation](https://github.com/polardb/polardb-pg-docs); used as the logo of [PolarDB for PostgreSQL](https://github.com/polardb/PolarDB-for-PostgreSQL) | [PolarDB logo PNG](https://raw.githubusercontent.com/polardb/polardb-pg-docs/main/docs/.vuepress/public/images/polardb.png) |
| `starrocks.svg` | [StarRocks documentation](https://docs.starrocks.io/) (navbar logo; source `docs/docusaurus/static/img/logo.svg` in [StarRocks/starrocks](https://github.com/StarRocks/starrocks)) | [StarRocks symbol SVG](https://docs.starrocks.io/img/logo.svg) |
| `doris.svg` | [Apache Doris brand assets](https://doris.apache.org/brand-assets/) | ["Doris Logo - Stacked" for light backgrounds](https://doris.apache.org/images/brand-assets/doris-logo-stacked-primary.svg) |
| `valkey.svg` | [Valkey website](https://valkey.io/), [valkey-website repository](https://github.com/valkey-io/valkey-website) | [Valkey logo SVG](https://valkey.io/img/Valkey-logo.svg) |
| `upstash.svg` | [Upstash brand assets](https://upstash.com/brand) | [Upstash icon for white backgrounds](https://upstash.com/logo/upstash-icon-white-bg.svg) |
| `dragonfly.svg` | [DragonflyDB website](https://www.dragonflydb.io/) (declares it as the SVG favicon), [DragonflyDB terms of use](https://www.dragonflydb.io/terms) | [Site favicon SVG](https://www.dragonflydb.io/favicon.svg) |
| `garnet.png` | [Garnet website](https://microsoft.github.io/garnet/), [microsoft/garnet](https://github.com/microsoft/garnet) | [Garnet diamond logo PNG](https://microsoft.github.io/garnet/img/garnet-logo-diamond.png) |
| `thanos.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [Thanos colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/thanos/icon/color/thanos-icon-color.svg) |
| `mimir.svg` | [Grafana repository, grafana GitHub organisation](https://github.com/grafana/grafana/tree/main/public/img/alerting), [Grafana Labs trademark policy](https://grafana.com/trademark-policy/) | [Mimir logo SVG](https://raw.githubusercontent.com/grafana/grafana/main/public/img/alerting/mimir_logo.svg) |
| `cortex.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [Cortex colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/cortex/icon/color/cortex-icon-color.svg) |
| `victoriametrics.png` | [VictoriaMetrics README, "VictoriaMetrics Logo"](https://github.com/VictoriaMetrics/VictoriaMetrics#victoriametrics-logo) | [VM_logo.zip](https://raw.githubusercontent.com/VictoriaMetrics/VictoriaMetrics/master/VM_logo.zip), member `LOGO PACK GITHUB/SYMBOL/symbol-black.png` |
| `grafana.svg` | [Grafana repository](https://github.com/grafana/grafana/tree/main/public/img), [Grafana Labs trademark policy](https://grafana.com/trademark-policy/) | [Grafana icon SVG](https://raw.githubusercontent.com/grafana/grafana/main/public/img/grafana_icon.svg) |
| `starburst.svg` | [Starburst brand guidelines: logos](https://brand.starburst.io/logos) | [Starburst logo kit ZIP](https://brand.starburst.io/files/logos.zip), member `logos/logomark/black/starburst_brandmark_black.svg` |
| `k3s.svg` | [CNCF artwork](https://github.com/cncf/artwork) | [K3s colour icon SVG](https://raw.githubusercontent.com/cncf/artwork/main/projects/k3s/icon/color/k3s-icon-color.svg) |
| `greenplum.png` | Greenplum symbol: the greenplum-db organisation avatar (140 x 140, served by GitHub's avatar service) | [Organisation avatar](https://avatars.githubusercontent.com/u/14097842?v=4) |
| `mariadb.svg` | [MariaDB logos](https://mariadb.com/about-us/logos/), [MariaDB trademarks](https://mariadb.com/trademarks/) | [Black vertical logo SVG](https://wp.mariadb.com/wp-content/uploads/2026/11/mariaDBLogo_vertical_dark_transparent.svg) (the page's [download link](https://mariadb.com/download/53702) serves the same bytes) |
| `cockroachdb.svg` | [Cockroach Labs GitHub organisation, `langchain-cockroachdb`](https://github.com/cockroachdb/langchain-cockroachdb/blob/main/assets/cockroachdb_logo.svg) | [Cockroach Labs brandmark SVG](https://raw.githubusercontent.com/cockroachdb/langchain-cockroachdb/main/assets/cockroachdb_logo.svg) |
| `scylladb.svg` | [ScyllaDB media kit](https://www.scylladb.com/media-kit/) | [ScyllaDB logo kit 2024 ZIP](https://www.scylladb.com/wp-content/uploads/ScyllaDB-brand-kit-logo-2024.zip), member `ScyllaDB-brand-kit-logo/Logo/Web/SVG/scylladb-logo-vertical.svg` |

### Checksums

| File | Bytes | SHA-256 |
|---|---:|---|
| `aws-documentdb.svg` | 3985 | `b4d0f90ebf8c10159c8b3cf85eaa6e4818556927d30daa2aa1c13741354d04de` |
| `aws-eks.svg` | 2610 | `ece93ecb16e9710c87f2135de8e8057ff17da16eb817d6d2a27a55520fed2f08` |
| `aws-elasticache.svg` | 4465 | `60a2c052a36c06462498aece6aa544a42670a1cdf05bf5ee05f2670b220b4f88` |
| `aws-keyspaces.svg` | 2549 | `fa347d06170a7da1e3525cef9c329cf8947cbe2f76292d90b0bd8bef856eecc1` |
| `aws-opensearch.svg` | 2350 | `483d2233926ebc15fbfb8a6baaf5076b4216cc6efdd056e33444d3c9fc705179` |
| `aws-rds.svg` | 2672 | `b7dc3f25c4a18d8f40dc85cacbc5fa509c7541e63b4125292242eed1ab31adad` |
| `azure-aks.svg` | 3943 | `81954a481503057498d543a51863b2eb4673284e109662cdc421051d11dba25c` |
| `azure-cache-redis.svg` | 2807 | `39a62e5d2c573c1a3184eb2a818a3be09b25716e976acbada62c777c41a67ab9` |
| `azure-cassandra.svg` | 3068 | `efb7956ac1b63aae82f8bfb7e3e95c0aa88e2caf324c6f75f1acadbd9affc4f1` |
| `azure-databricks.svg` | 456 | `ea499005b9f8ed811d14e5e048fe6138ca2aa89250373c71e32f39793acb8dc4` |
| `azure-mysql.svg` | 1616 | `c04e7d46a45d722e9fde06976843263cb1ef80fd6a5005dd9ce720aa130688ee` |
| `azure-postgresql.svg` | 3564 | `8232e8ecfbd607ce3b54646199805a823646d71e552f9aa71e7a3952cde35620` |
| `azure-sql-mi.svg` | 2583 | `ecf79797b7a8f7825eb4c987b1269d035fe3408f999149f29106fc24a62b0452` |
| `azure-sql.svg` | 1666 | `c2c81766a697920a115ad8532642bccaab257d1d3db7388aa35fdbb9a6db8be0` |
| `cockroachdb.svg` | 3006 | `7988aeff299871249ddeb5e276ec5ad50402ee8a782be6c983149529abaff292` |
| `cortex.svg` | 1923 | `50d2e99b22b9fb5aa0ec262638fa5f3b3c7373a949f336859441043db968b95a` |
| `doris.svg` | 2358 | `bf11d8d1cefff1ffea8677be6aaf6217f8998f3670972f87ef879c5823a6f687` |
| `dragonfly.svg` | 3856 | `b6aa0baec807017f35e05f2260d2909d9b8d9b2a8ead19775858bc5e0a9b23ec` |
| `edb.png` | 128279 | `41eccc7e7526b451ecc0b36555d077ea1085fa5a8d5360eef7c2cff6995b5e6a` |
| `garnet.png` | 96830 | `b0b4c7104958fd74043257d83bede01bdf63b9748bc800bc18d073d46e23d98c` |
| `gcp-alloydb.svg` | 2101 | `6bbb783296fb9d7426c2247ebf1453a3f89842040e46d1d2588676fc6115aeee` |
| `gcp-cloud-sql.svg` | 2531 | `4e0d3d2049a67f64e5a2c88f836bf2d1786f809a72d82279b18906ec9d6b35c9` |
| `gcp-gke.svg` | 2907 | `6a33654c499bc4c38012661875a1a8c1bf2cdf01be8fdcc90d1c6504657878ec` |
| `gcp-memorystore.svg` | 2161 | `f9ecdabe95c8c30cd6adabcb3e521f7fe462eacd63ce19021222a2a1b41a7266` |
| `grafana.svg` | 5690 | `50806d5a65779e79f746398570e0fc322d5de55e19f0be87a275963f0947bb5e` |
| `greenplum.png` | 3824 | `7d5dc207c10736449c4494e587530fa7f94b184749d45823df725e2ede8fafe6` |
| `k3s.svg` | 957 | `c6231341ab65571c02701703e627875b6f426ed819aad5f263f1ea8c18b8af09` |
| `mariadb.svg` | 7443 | `b76b00a0af6056d521ed83c338d635895fa1be052dcf849d74d4fe2b2799d58b` |
| `mimir.svg` | 1065 | `b9b6a19d8c77c83ad47781a864a69fb2500ed689d33308c3b52dcd4f30a9c1dc` |
| `neon.svg` | 275 | `2b1aaef0b2de5d30b9a00778a0a9b9df2255be08a939a9521f3c93a46de521cf` |
| `oceanbase.svg` | 2417 | `837ebd84390687dddd01f6880eb2cc88f1ece2cf65b2441ad6295d3db46d07bc` |
| `oracle.svg` | 272 | `1ef2737f81314112ebad6d490c647d45b46d4ba0e3a16cf441aaaf32dac92388` |
| `percona.svg` | 822 | `bceb643a5005163837fca9de57e435dc03e9a771c21af869c8ea1261fe89d4ed` |
| `planetscale.svg` | 471 | `f15c3ee6cbf65c2324ab73b6f36c376aa55a303ebccab42e26a14c8ac1155c2d` |
| `polardb.png` | 10503 | `75937c3d3bdbd72b94a4fb1765c668e95a1c4e140c0ed5bddd24b4b3709b0ba4` |
| `postgis.svg` | 94811 | `065e9730a63ce947d0d0427479d3000c41460c5b470bc200de24e2ee90ec8505` |
| `scylladb.svg` | 11312 | `be7c587c651c27d3895d4c01d8be53202a23e068c429995e0d9f5f509e4b22de` |
| `singlestore.png` | 42686 | `0290f697d15d7944ea71d7832bb9a7fe0d54845a7d5dc1f04e4357308f89dc59` |
| `starburst.svg` | 4424 | `8e4d2bbadafc5e512ef31b33a85caf11ded8820228890339e845082a064029b5` |
| `starrocks.svg` | 2270 | `d858020733c75f51c32f55f1b5785cf12dc7c7d3729bb641148f0d8094574b1b` |
| `supabase.svg` | 1107 | `eb9ffcd100ad26228a578100ba7cbfc5b383e4995338d122abdc6a3b4d210ed3` |
| `thanos.svg` | 960 | `035fafccdce875c061d1f251ea1124090e13a759ed5e344be450114c9dd166c5` |
| `tidb.svg` | 721 | `db4e6ad7d66bd99f3b5ec25c0c8621fab90b24e811e11617d3f53443193efb7e` |
| `timescale.svg` | 2494 | `e97d189a745c2fbd98ca04123be195ee3e40aa99da2f3ffef73724325d049fd0` |
| `upstash.svg` | 1619 | `e96aca390468d5cf86d888c7b7e640c2379ec1660d464f125ab6e0f3c3c520c2` |
| `valkey.svg` | 1365 | `36a2375e56420a570f6a0346dde019314204c05f5326c0f00d39114e63397f37` |
| `victoriametrics.png` | 11017 | `f1c040d1c058e8f04305f422550d68c19f730469382be0cad95128393eb4c271` |
| `vitess.svg` | 3400 | `c6d8100e52eb19668bbfa9950c3d84e72dd46c4a4d4ef9cd5ca24c49926289db` |
| `yugabytedb.svg` | 1521 | `a3aa03d4dd0e6871e6bf3bc95bee8d7bc482fb0764b77ffe9e552766b1b41234` |

### Packages

Each package was downloaded once; only the listed members were extracted, by exact name, with path-traversal and
symlink checks. The cloud packages are the ones listed in the sections above.

| Package | Bytes | SHA-256 |
|---|---:|---|
| [AWS icon package 2026-07-31](https://d1.awsstatic.com/onedam/marketing-channels/website/public/shared/architecture-icon-release/Icon-package_07312026.5846e92413caa21490223536cc97f1269e44fa92.zip) | 13988918 | `d2d166c453526471749d520e0db022c459abef759d2946cf2dd1d1c992dc6526` |
| [Azure Public Service Icons V25](https://arch-center.azureedge.net/icons/Azure_Public_Service_Icons_V25.zip) | 1045239 | `f293d99252b3638557dd08ccbff9f2529083473d39b373fb2af29a7200351339` |
| [Google Cloud core product icons](https://services.google.com/fh/files/misc/core-products-icons.zip) | 318678 | `6531a10f58bc599c24d9a455d81dd757c1a03c3c43da9cddf639b859c1c1eece` |
| [Google Cloud category icons](https://services.google.com/fh/files/misc/category-icons.zip) | 795968 | `e5bc3abd3527dc2500e9bff7f15870783e2c764129c49b7cd4c1b4e105345002` |
| [YugabyteDB logo 2023](https://www.yugabyte.com/wp-content/uploads/2023/04/yugabytedb-logo-2023.zip) | 22748826 | `74263686a35443bdfccd0ef3bc8e7c739568f548d8c86b8c36aba1842b4ec78c` |
| [TiDB logo and symbol](https://static.pingcap.com/files/2024/09/26051203/TiDB-Logo-Symbol.zip) | 165238 | `98b5fbb2dfe473d5a1667f73160cf918c7a6a10afe38c4f57e6c38362b7b1823` |
| [VictoriaMetrics VM_logo.zip](https://raw.githubusercontent.com/VictoriaMetrics/VictoriaMetrics/master/VM_logo.zip) | 4461282 | `4cd326f88067504378b2d1420859f8140d533c41b9317a09c394791f2df478be` |
| [Starburst logo kit](https://brand.starburst.io/files/logos.zip) | 272969 | `e1340557110f37972e3bc56a534c53be17e24a50da47c18d07e4ba650dc92724` |
| [ScyllaDB logo kit 2024](https://www.scylladb.com/wp-content/uploads/ScyllaDB-brand-kit-logo-2024.zip) | 19103646 | `b977ce28d5d601165e1b5a501934c8e7ff37e5ca1971c962733c55fc858cad86` |

### Asset notes

- **Cloud icon packs (AWS, Azure, Google Cloud):** same packages and terms as in
  the sections above. AWS icons are for architecture diagrams and technical
  material and the AWS Trademark Guidelines limit fair use to plain text; the
  Azure icons are permitted in architectural diagrams, training material and
  documentation, with every other use reserved for Microsoft's explicit
  permission and the full service name shown near the icon; Google Cloud icons
  are offered for diagrams and documentation, and other use of Google brand
  features needs Google's approval. The 32 px AWS variants were taken; the
  Amazon DocumentDB 32 px file declares a 64 x 64 viewBox as shipped. The
  Google Cloud overview (May 2026) gives Cloud SQL, AlloyDB and GKE unique core
  icons and assigns Memorystore the shared Databases category icon, so
  `gcp-memorystore.svg` is byte-identical to the shipped `firestore.svg`; the
  legacy console icons were not used. The Azure pack also contains an Azure
  Managed Redis icon (alternate). `azure-aks.svg` appears in the pack under both
  `containers/` and `compute/` with the same bytes.
- **Oracle:** the Oracle "O" in Oracle Red `#C74634`, from the root of Oracle's
  official `oracle/mcp` repository (Oracle's MCP servers), added in commit
  `3a897a8769a29a3fb50e95cfce33f6cf8d4a5757` on 2025-08-09. Its path is
  identical to the "O" of the full Oracle logo in Oracle's
  `oracle/langchain-oracle` repository (`.github/images/oracle-logo.svg`).
  Oracle's guidelines cover both the Oracle logo and the "O Tag". The
  repository's UPL-1.0 licence grants no trademark rights. Alternate: the
  oracle.com site icon (`apple-touch-icon`, 180 x 180 PNG, `#E55844`), which
  www.oracle.com pages declare. oracle.com
  still answers HTTP 403 to automated page requests, but this static icon was
  served (HTTP 200) by Akamai Image Manager. Its bytes may differ for other
  clients, Oracle's
  [logo guidelines](https://www.oracle.com/legal/logos/) require Oracle's written
  authorisation to use the Oracle logo or the O Tag. Non-partners request it
  from trademar_us@oracle.com with a written summary and a mock-up of the
  proposed use; the stated turnaround is about one week. Authorised users must
  not alter the logos. The OCI diagram toolkit is published only as PowerPoint,
  draw.io and Visio files, and the brand-kit artwork still needs an Oracle
  account. A third Oracle-org candidate, the OCI Designer Toolkit's
  `okitclassic/okitserver/static/svg/oracle.svg` (`#E55844`), was not used because its shape sits at the top of its viewBox.
- **Supabase:** the green symbol, which the brand page recommends "in small
  spaces, such as avatars, app icons, or a row of partner logos". The page
  asks that the logos be used only to refer to Supabase and not be changed or
  recoloured; Supabase and its logo are trademarks of Supabase Inc. No
  permission request is described.
- **Neon:** the logomark for light backgrounds (`#37C38F`); the dark-background
  file (`#34D59A`) is an alternate. The brand page allows the logomark where the
  full logo does not fit or where only the symbols of several brands are shown,
  and forbids editing, distorting, recolouring or reconfiguring the logo. The
  page notes that Neon is part of the Databricks platform; it states no
  permission requirement.
- **TimescaleDB (Tiger Data):** Timescale, Inc. now trades as Tiger Data. The
  brand page offers only Tiger Data logos (horizontal and badge, in black,
  white and yellow), and the TimescaleDB repository README shows the Tiger Data
  logo, so the Tiger Data badge represents TimescaleDB; there is no separate
  TimescaleDB mark. The black badge is for light backgrounds. The
  [trademark policy](https://www.tigerdata.com/legal/trademarks) (updated
  2025-06-17) allows the marks only to identify Tiger Data products, requires
  the trademark notice ("[TRADEMARK] is a trademark of Timescale, Inc., d/b/a
  Tiger Data"), forbids editing or recolouring logos or using them so small that
  design features are lost, and asks for clear space at least the height of the
  logo. Questions: legal@tigerdata.com.
- **PostGIS:** the image postgis.net shows as its brand in the site header
  (byte-identical to the site's SVG favicon): a 400 x 400 globe drawing of
  about 95 KB. The logo in the PostGIS source documentation is a PNG. No
  PostGIS logo-usage policy was found; PostGIS is an OSGeo community project.
- **YugabyteDB:** the colour logomark from the official 2023 logo package; the
  dark-background version is an alternate. The bundled logo guideline (Feb 2023)
  allows the logomark alone when the full logo does not fit (for example as a
  favicon or user picture), sets a 20 px minimum height and clear space equal to
  the logomark's size, forbids transformation, effects and outlines, and forbids
  using the dark-background version on light backgrounds and vice versa. No
  trademark policy page was found; the website terms reserve all rights in
  logos.
- **EDB:** EDB's current brand uses the "Loop" icon. Every EDB SVG found that
  carries it embeds the icon as a PNG and fails the embedded-raster rule: the
  website header `logo2.svg` and the documentation lockups
  `edb_postgres_ai_lightmode.svg` (the same bytes), `edb_postgres_ai_darkmode.svg`
  and `edb_landscape_color_grey.svg`. The brand portal (edb.frontify.com) is a
  JavaScript application that a plain HTTP client cannot read, and nothing was
  downloaded from it. The SVG symbols in EDB's GitHub organisation are the
  earlier elephant mark, which the brand book says has been retired in favour of
  Loop. The mark is therefore the 512 x 512 `apple-touch-icon` of EDB's
  documentation site. The
  brand book digest asks for clear space, unaltered colours and no stretching
  or attached text. The [trademark page](https://www.enterprisedb.com/trademarks)
  lists the logos as trademarks of EnterpriseDB Corporation and grants no logo
  licence. Questions go to legal-notices@enterprisedb.com; "allow several weeks
  for a response".
- **Greenplum:** the open-source Greenplum Database logo (symbol and "GREENPLUM
  DATABASE" wordmark, `#80BC00`), from the root of the official
  `greenplum-db/gpdb-archive` repository, last changed in commit
  `cf201991b7814e7f4f46ac8b830088686e1e0900` ("Fix the opensource GDPB logo",
  2023-03-28). The repository is archived (last push May 2024); the product
  continues as VMware Tanzu Greenplum from Broadcom, and greenplum.org now
  fails TLS hostname verification (not bypassed). No symbol-only file exists, so
  this is a wordmark lockup. Alternate: the greenplum-db organisation avatar, a
  140 x 140 PNG produced by GitHub's avatar service rather than a vendor-published
  file. No Greenplum or Broadcom logo-usage terms were found. The card uses that avatar, the symbol alone, as `greenplum.png`: the wordmark lock-up is unreadable on a 32 px tile.
- **PlanetScale:** the black logo mark, which the brand page recommends "when
  space is constrained". The brand page states no terms; the
  [site terms](https://planetscale.com/legal/siteterms) forbid using
  PlanetScale's Brand Features without its prior written consent.
- **Vitess, Thanos, Cortex, K3s:** CNCF artwork under the Linux Foundation
  trademark usage guidelines quoted in the repository's `LICENSE.md`, as for
  Kubernetes and Prometheus above. Logos may link to the
  project and support accurate compatibility statements without implied
  endorsement; showing them on websites or marketing that promote your products
  needs written permission. Last changes: Vitess
  `e6aba42afaf413ce86832a8c0e5a34abdc676bdc` (2025-05-27), Thanos and Cortex
  `40e2e8948509b40e4bad479446aaec18d6273bf2`, K3s
  `ee5463f22889cecad7f69cb1892578e474ffa415`. k3s.io calls K3s a CNCF Sandbox
  project and refers to the Linux Foundation's trademark page. The CNCF
  "dragonfly" project is an unrelated P2P distribution system, not DragonflyDB.
- **TiDB:** the red TiDB symbol (`#DC150B`) from the "Download SVG and PNG
  Assets" package on the brand guidelines page. The page sets clear space and
  minimum sizes, shows incorrect uses, and refers questions to
  mkt-team@pingcap.com. PingCAP publishes no trademark policy.
- **SingleStore:** the brand page offers only horizontal lock-ups (black and
  white ZIPs) and prefers "solid black or solid white in a horizontal
  orientation". The square symbol is available only as the site's 512 x 512
  `apple-touch-icon`, which is the mark here; the black lock-up is an
  alternate. The icon draws the black symbol on a white rounded square with
  transparent corners. The brand page forbids stretching, reversing, flipping
  or altering logos, and the [terms](https://www.singlestore.com/terms/) forbid
  using SingleStore's Marks without its prior written consent.
- **Percona:** the full-colour logomark (`#653DF4`) from the official brand
  page. Downloads are Google Drive files linked from that page; the page's own
  one-colour preview SVG is an alternate. The
  [trademark policy](https://www.percona.com/trademark-policy/) covers
  Percona's "distinctive visual icons and logos". It requires Percona's
  written permission to use a mark in the name, URL or another identifying
  characteristic of a product or service, except for nominative fair use and
  GPL redistribution. Questions: trademarks@percona.com.
- **OceanBase:** the symbol from OceanBase's official design-system repository,
  introduced in commit `5512e6887ef8ca0c7423e4aaaaa71e0f9462261a` ("replace
  OceanBase brand logo across ui, icons and doc site", 2026-06-14); the main
  repository's horizontal logo (commit `a7d679d886cb0931f79c0deb61ef5cb2520a534d`,
  2026-07-14) is an alternate. en.oceanbase.com, including its brand page,
  answered automated requests with a Vercel Security Checkpoint (HTTP 429), so
  OceanBase's own brand terms could not be read; no other usage terms were found.
- **PolarDB:** the PolarDB logo (orange polar bear, 220 x 220 PNG) used as the
  README logo of PolarDB for PostgreSQL, from the docs repository of Alibaba
  Cloud's official `polardb` GitHub organisation (commit
  `d10f527555ae396d5d9862b08eaa0c53f9582ddf`, 2026-03-04). No SVG exists. The
  PolarDB-X site's SVG favicon embeds a PNG (rejected), and Alibaba Cloud's
  product page offers no downloadable icon. No Alibaba Cloud logo-usage
  guidelines were found. Its published program terms, for example the
  [Alibaba Cloud Academy membership terms](https://www.alibabacloud.com/help/en/legal/latest/alibaba-cloud-academy-membership-program-alibaba-cloud-academy-membership-program)
  (updated 2026-08-28), forbid using Alibaba Cloud names, logos, icons or
  brands for marketing, publicity or other purposes without prior written
  consent. Treat this mark as needing written permission.
- **StarRocks:** the symbol in the documentation site's navbar, byte-identical
  to `docs/docusaurus/static/img/logo.svg` in the StarRocks repository (commit
  `c16c9d5dd2799304109f2ae6b600ecd2ae396de2`). starrocks.io/brand returns 404.
  The site footer names the StarRocks Project a Series of LF Projects, LLC, and
  refers to lfprojects.org for the trademark policy. That policy is the LF
  Projects policy that also covers Presto and Trino: a logo may link
  to the project home page, and websites or marketing that promote your products
  need written permission.
- **Apache Doris:** the "Doris Logo - Stacked" for light backgrounds (the most
  square of the offered lockups; no symbol-only file). The official brand assets
  page (added 2026-07-12) offers the SVGs "for articles, presentations,
  community events, integrations, and other materials that reference the
  project". It states that Apache Doris, Doris and the Doris logo are trademarks
  of The Apache Software Foundation and asks for clear and accurate references.
  The ASF trademark policy (see Cassandra above) still
  requires approval for logo use other than a hyperlink to the project. The
  page also offers "Apache Doris" lockups. The ASF's own original
  (`comdev/project-logos/originals/doris.svg`) is a wide 952 x 341 logo and was
  not used.
- **Valkey:** the symbol served by valkey.io (byte-identical to
  `static/img/Valkey-logo.svg` in the website repository). The site footer
  states that Valkey and the Valkey logo are trademarks of LF Projects, LLC,
  and links the LF Projects trademark policy (as for StarRocks). No brand page
  was found (`/brand/`, `/branding/` and `/logo/` return 404).
- **Upstash:** the icon for white backgrounds from the brand page, which states
  no usage terms; the terms of service contain no logo licence. The SVG has no
  viewBox (118 x 118 width and height) and scales as an image. The brand page
  also contains text addressed to AI agents about a free database; it was
  treated as page content and not acted on.
- **DragonflyDB:** no brand or press page exists. The mark is the site's
  declared SVG favicon: a white dragonfly on a dark rounded square. It contains
  a `prefers-color-scheme: dark` media query that inverts the colours, so it may
  render light-on-dark or dark-on-light depending on the viewer's colour scheme.
  The [terms of use](https://www.dragonflydb.io/terms) grant no licence to
  DragonflyDB's marks and logos ("you will avoid using any of those marks,
  except as permitted herein").
- **Microsoft Garnet:** only PNG logos exist (website `img/` and repository
  `website/static/img/`). The diamond is the website's navbar logo (added in
  commit `e72aab560d7c0701d4ff053fe321a0d8d25cc35b`). It is RGB without
  transparency: the white background and drop shadow are part of the image.
  The README subjects Microsoft trademarks and logos to Microsoft's Trademark &
  Brand Guidelines, under which many logo uses need a licence.
- **Grafana and Grafana Mimir:** the Grafana icon (`public/img/grafana_icon.svg`)
  and the Mimir logo (`public/img/alerting/mimir_logo.svg`, commit
  `18862a22a65dd2238408d223ba0e9fe22ad4f277`, "Alerting: use mimir logo") come
  from Grafana Labs' main repository. The grafana/mimir repository ships PNG
  logos only. Grafana Labs' trademark usage policy forbids altering the logos (scaling is
  allowed). It also forbids use on any website or in commercial or marketing
  contexts without Grafana Labs' express written permission:
  hello@grafana.com.
- **VictoriaMetrics:** the black symbol PNG (404 x 466, transparent) from the
  official `VM_logo.zip` linked in the README; the package contains PNG, JPEG
  and Illustrator files but no SVG, and the white symbol is an alternate. The
  README rules (commit `df665a13c90042d6b324aa6a0a8ddf13b7289ca0`) ask for
  clear space and unchanged proportions, spacing and alignment, and allow
  resizing. No trademark policy is published.
- **Starburst:** the brandmark from the logo kit linked on Starburst's brand
  site. The kit's default `logos/logomark/starburst_brandmark.svg` uses
  `currentColor`. The black
  variant with an explicit `#080808` fill was taken so that it renders the same
  as an image. The brand site requires the approved variations without
  alteration, a 24 px minimum logomark height and clear space of half the
  logomark. Starburst and Starburst Data are registered trademarks of
  Starburst Data, Inc.; no third-party logo licence is stated.
- **MariaDB:** MariaDB plc replaced its logo files this month (uploads dated
  2026-11-09). The logos page asks not to rotate or recolour the logo (use
  only the supplied versions), to scale it proportionally and to leave white
  space around it; questions go to marketing@mariadb.com. The trademark page
  requires the established graphical form and colours and asks for advice and
  clearance from MariaDB before marketing materials use its marks outside
  Finland. No sea-lion-only file is offered; the vertical lockup is the most
  square. The white version
  ([SVG](https://wp.mariadb.com/wp-content/uploads/2026/11/mariaDBLogo_vertical_light_transparent.svg))
  is for dark backgrounds.
- **CockroachDB:** Cockroach Labs offers no brand or press page with logo
  downloads (its press page has none; `/brand/`, `/media-kit/` and
  `/press-kit/` return 404) and publishes no trademark guidelines; its
  [website terms](https://www.cockroachlabs.com/website-terms-of-use/) reserve
  all rights in site content. The file is from Cockroach Labs' own GitHub
  organisation, titled "Cockroach Labs brandmark", and was introduced on
  2026-11-10 in commit `76ad91db648fbf41fbfea854ff38bc4cc2121e47`, replacing
  the earlier insect mark. Its geometry matches the brand image in the
  cockroachlabs.com footer, which is inline and not downloadable. The
  `claude-plugin` repository logo embeds a PNG and was not used. Black only.
- **ScyllaDB:** the media kit welcomes using the logos to link to the
  ScyllaDB website and the product mascots to represent an integration with
  ScyllaDB; it forbids modified versions, merging into your own logo and
  changes to colours, dimensions or added text. The kit has horizontal and
  vertical lockups only; the vertical one is closest to square. The
  symbol-only site favicon is an alternate (a scylladb.com theme asset, not
  part of the kit). The mascot kit was not downloaded.

### Not included

- **FerretDB:** www.ferretdb.com now shows a Squarespace "Website Expired" page
  (HTTP 404) and docs.ferretdb.io does not resolve, so the only official source
  is the FerretDB repository. Its documentation configuration uses
  `logo-dark.jpg` (dark artwork on an opaque white JPEG) in light mode and
  `logo-light.png` (white artwork on transparency) in dark mode; both are
  horizontal lockups and no SVG exists. The PNG was taken because the mark
  format allows only SVG or PNG; it is white and needs a dark tile. No FerretDB
  usage terms are published.
- **KeyDB:** no official logo file is published any more. keydb.dev and
  docs.keydb.dev only redirect to the `Snapchat/KeyDB-docs` repository, which
  contains no images. The `Snapchat/KeyDB` repository contains no KeyDB logo.
  The original EQ-Alpha GitHub organisation has no repositories and a default
  avatar. KeyDB belongs to Snap Inc.
- **Red Hat OpenShift:** Red Hat's brand standards offer product logos and
  technology icons through its Adobe brand portal, for Red Hat materials and
  for partner materials approved by Red Hat's partner team. Its
  [trademark guidelines](https://www.redhat.com/en/about/trademark-guidelines-and-policies)
  state that they give no permission to use a Red Hat logo. Such use needs
  "a separate, express written agreement with Red Hat"; contact
  trademarks@redhat.com. Nothing was downloaded.

These files retain their original geometry, colours, proportions and metadata. The SVGs contain no scripts, event
handlers, embedded foreign content, embedded raster images or remote references; the PNGs passed a decode check. Cards
show each one with the service's plain name on a light plate in every theme, never recoloured.

## Spanner connector addition (2026-11-11)

`spanner.svg` is the Cloud Spanner product icon from the same Google Cloud core product icons package as
`bigquery.svg`, under the Google Cloud terms recorded in the connector-card additions above. It was extracted by exact
name from that package (its SHA-256 is listed there), not downloaded again, and passed the same SVG checks.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `spanner.svg` | [Google Cloud icon library](https://cloud.google.com/icons), [product icons overview (PDF)](https://services.google.com/fh/files/misc/google-cloud-product-icons.pdf) | [Core product icons ZIP](https://services.google.com/fh/files/misc/core-products-icons.zip), member `Unique Icons/Cloud Spanner/SVG/CloudSpanner-512-color.svg` |

| File | Bytes | SHA-256 |
|---|---:|---|
| `spanner.svg` | 1735 | `8e6e5bd76d4f41afbd0f0056b5efa2335963f0dcfe7a0def3fa0bb393a02c700` |

## Vector database and search engine additions (2026-11-12)

Downloaded on 2026-11-12 for the vector database and search engine cards of the Add connection page, under the same
terms as above: intact vendor-provided artwork, not GateRook artwork, not covered by a blanket open-source licence,
identifying the services being connected without indicating sponsorship or endorsement. The notes record each vendor's
conditions; this record does not assert that any permission has been granted. Each vendor's cloud card (Qdrant Cloud,
Weaviate Cloud, Chroma Cloud, Meilisearch Cloud) shows the same vendor's mark. Weaviate, Chroma and Meilisearch publish
no brand or press page: their marks are the symbol of each vendor's logo as the vendor publishes it on its own website or
in its own GitHub organisation, recorded with the commit that last changed the file.

| File | Official source page | Exact downloaded asset |
|---|---|---|
| `pinecone.svg` | [Pinecone brand guidelines](https://www.pinecone.io/brand/) (Brand 4.0, updated October 2026) | [Logomark, black, for light backgrounds](https://www.pinecone.io/images/brand/pinecone-logomark-black.svg) |
| `qdrant.svg` | [Qdrant brand resources](https://qdrant.tech/brand-resources/) | ["Logomark Full Color" SVG](https://qdrant.tech/img/brand-resources-logos/qdrant-brandmark-red.svg) |
| `milvus.svg` | [Milvus artwork repository, milvus-io GitHub organisation](https://github.com/milvus-io/artwork); the same set is in the [LF AI & Data artwork repository](https://github.com/lfai/artwork/tree/main/projects/lfai-milvus) | [Milvus colour icon SVG](https://raw.githubusercontent.com/milvus-io/artwork/684ecb4b3de4b3171a7f4387afa64b7f81046c9c/icon/color/milvus-icon-color.svg) |
| `weaviate.svg` | [weaviate.io](https://weaviate.io/), source in Weaviate's [website repository](https://github.com/weaviate/weaviate-io) | [Weaviate symbol SVG](https://weaviate.io/img/site/weaviate-logo-icon.svg) (byte-identical to `static/img/site/weaviate-logo-icon.svg` in the website repository) |
| `chroma.svg` | [Chroma repository, documentation configuration `docs/mintlify/docs.json`](https://github.com/chroma-core/chroma/tree/main/docs/mintlify) | [Chroma symbol SVG](https://raw.githubusercontent.com/chroma-core/chroma/HEAD/docs/mintlify/images/favicon.svg) |
| `algolia.svg` | [Algolia newsroom](https://www.algolia.com/about/news) ("Looking for our logo?"), [Algolia brand guidelines](https://algolia.frontify.com/) | [Algolia logo pack ZIP](https://www.algolia.com/algolia-logo-pack.zip), member `algolia-logo-pack/SVG/Algolia-mark-blue.svg` |
| `meilisearch.svg` | [Meilisearch repository, `assets/`](https://github.com/meilisearch/meilisearch/tree/main/assets) | [Meilisearch symbol SVG](https://raw.githubusercontent.com/meilisearch/meilisearch/HEAD/assets/logo.svg) |
| `azure-ai-search.svg` | [Azure icons, Azure Architecture Center](https://learn.microsoft.com/en-us/azure/architecture/icons/) | Azure icons ZIP (as for SQL Server), member `Azure_Public_Service_Icons/Icons/ai + machine learning/10044-icon-service-Cognitive-Search.svg` |

### Checksums

| File | Bytes | SHA-256 |
|---|---:|---|
| `algolia.svg` | 1020 | `92490f716cf1752c0a909cd90412551f4537fbab2f2883be76a22d1a8ec49866` |
| `azure-ai-search.svg` | 1146 | `f7af1213ce089236593835cb5564d6d3f01358cba6488fe562aa8bbc8adc3b38` |
| `chroma.svg` | 945 | `dcbd0b1c44052cf5caaa3db9182bd7e8e70afeb7f82709b329371f4beff99cf6` |
| `meilisearch.svg` | 1315 | `9e9aecb57526b612666d146ad160ed85b9052d9a5ba1f54af983e903e297808e` |
| `milvus.svg` | 2369 | `1194acd695cdc8e7f713b0cf5f91d7e25f33922df6e01314f96ccd07d727a244` |
| `pinecone.svg` | 1829 | `34755f91827edbfd409d8edb8a7209b8704cc39af69ac7518640b1016db15add` |
| `qdrant.svg` | 1478 | `1642d28e5c93229b5f0d13546a4275e6120a8a6be159ac2ebaaf2f1a8feddaca` |
| `weaviate.svg` | 4216 | `8d6fa8dcfb40e749ae92921c25fb77a6f590ebec1d19f6c52711ff28dd78413b` |

### Packages

The Algolia member was extracted by exact name, with path-traversal and symlink checks; the archive's other members
(SVG, PNG and EPS logos, an Illustrator EPS source, `.DS_Store` files and macOS resource forks under `__MACOSX/`) were
not extracted into the kit. The Azure member comes from the copy of the Azure package downloaded on 2026-11-11, whose
SHA-256 still matched the connector-card record above; it was not downloaded again.

| Package | Bytes | SHA-256 |
|---|---:|---|
| [Algolia logo pack](https://www.algolia.com/algolia-logo-pack.zip) | 4716275 | `ae126df090abba1f525e107b1e8a3af6a8aff4923a3522e6896232aab20507fd` |

### Asset notes

- **Pinecone:** the black logomark, which the brand page offers "for tight spaces and grids of partner logos, where
  people already know the brand"; black files are for light backgrounds. The page's usage section lets anyone, without
  asking, use the Pinecone name and logo to refer to Pinecone and its products accurately and show that a product
  uses or integrates with Pinecone. It forbids suggesting that Pinecone sponsors, endorses or partners with you without
  an agreement, using Pinecone in the name of your company, product or domain, changing the logo, making it more
  prominent than your own name, and putting it on merchandise; other uses, such as co-marketing and event signage, need
  asking first. The logo must never appear below 24 px tall: the 32 px card tile (4 px padding) shows the logomark
  exactly 24 px tall, so a smaller tile must not use it. Black or white only. Pinecone is a registered trademark of
  Pinecone Systems, Inc. Alternate: the square black-on-white icon for app directories, whose built-in margin would
  shrink the mark below that minimum.
- **Qdrant:** the full-colour logomark (Amaranth `#DC244C` with a darker and a lighter shade); the black logomark is an
  alternate. The brand page says that use of the Qdrant trademark must adhere to Qdrant's "Trademark Standards for Use"
  and directs permission requests and questions to info@qdrant.com. The page does not link those standards, and
  Qdrant's terms and conditions contain no trademark clause, so nothing grants a licence.
- **Milvus:** the colour icon (`#33B5F1`) from the official milvus-io artwork repository, last changed in commit
  `684ecb4b3de4b3171a7f4387afa64b7f81046c9c` (2026-09-22, "fix: circles drawn as arcs in SVG render incorrectly"); the
  black icon is an alternate. Milvus is an LF AI & Data Foundation project, and zilliz.com states that "LF AI, LF AI &
  Data, Milvus, and associated open-source project names are trademarks of the Linux Foundation". Both artwork
  repositories make the files available under the
  [Linux Foundation trademark usage guidelines](https://www.linuxfoundation.org/legal/trademark-usage): compatibility
  statements must be accurate and imply no sponsorship or endorsement; a logo may link to the project's home page; using
  a logo on websites or other marketing materials that promote your products or services, or in any other commercial
  context, needs written permission (trademarks@linuxfoundation.org; questions about Milvus: info@lfai.foundation).
  Zilliz's brand page also offers Milvus logo files, under the Zilliz terms described below; they were not downloaded.
- **Weaviate:** weaviate.io has no brand or press page (`/brand`, `/press`, `/company/press`, `/brand-assets` and
  `/media-kit` return 404), and the press section of its About page lists articles only. `weaviate.svg` is the Weaviate
  "W" symbol served by weaviate.io, byte-identical to the website repository's copy, last changed in commit
  `4e8c78603d34561a5e3bc311d9a560109e0523bd` (2024-04-26). The same symbol and gradients lead the logos that the
  website and the documentation configure for their navigation bar (`weaviate-logo-horizontal-light-1.svg` and
  `-dark-1.svg`) and the stacked logo that weaviate.io declares as its organisation logo in structured data
  (`weaviate-logo-square-dark.png`, an alternate that is unreadable on the tile). The 2026 home page header uses a
  newer two-colour outline variant (`/img/site/2026/weaviate-logo-2-colours-dark-green.svg`, symbol
  `weaviate-website-logo.svg`, commit `6b2f898357a0397fd28abbe19be6b9ebb293b492`, 2026-06-03) drawn in light colours
  for dark backgrounds: it almost disappears on the light plate, no version for light backgrounds was found, and the
  marks are never recoloured. weaviate.io publishes no logo or trademark policy and grants no licence; the site belongs
  to Weaviate B.V., Amsterdam (legal@weaviate.io).
- **Chroma:** trychroma.com has no brand or press page (`/brand`, `/press`, `/brand-assets`, `/media-kit` and
  `/press-kit` redirect to its sign-in page). `chroma.svg` is the Chroma symbol (three discs, `#327EFF`, `#FFDE2D` and
  `#FF6446`) that Chroma's documentation configuration in its official repository declares as the documentation
  favicon, last changed in commit `5d40e7f9ed8bacd54c087e56b6eb3c6e38e300b8` (2026-02-02). The same symbol in the same
  colours leads the "Chroma" wordmark of the documentation logo `docs/mintlify/images/light-logo.svg` (alternate;
  unreadable on the tile). The [website terms](https://www.trychroma.com/website-terms) license the site for personal
  use only and reserve the rights in its graphics and design; no trademark or logo policy is published.
- **Algolia:** the blue mark (`#003DFF`) from the logo pack that Algolia's newsroom and site-wide banner offer
  ("Looking for our logo? We got you covered!"). The pack holds the logo and the mark (plain, and on a circle, a
  rounded square and a square, in blue and in white); the plain blue mark suits the light plate, and the rounded blue
  mark is an alternate. The newsroom's "Brand guidelines" link leads to Algolia's Frontify portal
  (`algolia.frontify.com`), a JavaScript application that a plain HTTP client cannot read, so its rules were not read.
  Algolia's [terms of service](https://www.algolia.com/policies/terms) and legal pages grant no logo licence; press
  contact PR@algolia.com.
- **Meilisearch:** meilisearch.com has no brand or press page (`/press`, `/brand`, `/press-kit`, `/brand-assets` and
  `/media-kit` return 404). `meilisearch.svg` is the symbol from the official repository's `assets/logo.svg`, last
  changed in commit `372f4fc924f36319c921fd36fbdc354d96b1d974` ("Replace logo", 2022-01-26). The same symbol and
  `#FF4E62` to `#FF5CAA` gradients lead the wordmark in the repository's `meilisearch-logo-light.svg` (alternate) and
  in meilisearch.com's header logo (`/logo.svg`, a white wordmark for dark backgrounds). The
  [terms of use](https://www.meilisearch.com/terms-of-use) say that Meilisearch's logos and brands are protected by
  intellectual-property rights and grant no licence to them.
- **Azure AI Search:** Azure Architecture Icons V25, under the Microsoft terms recorded above for SQL Server and Azure
  Cosmos DB: diagrams, training material and documentation, with every other use reserved for Microsoft's explicit
  permission, and the full service name next to the icon. The package still names this icon after the service's former
  name; Microsoft's [Azure AI Search FAQ](https://learn.microsoft.com/en-us/azure/search/search-faq-frequently-asked-questions)
  confirms that Azure Search, Azure Cognitive Search and Azure AI Search are the same product, renamed in October 2019
  and October 2023. Byte-identical copies are under `app services/` and `web/`; `03321-icon-service-Serverless-Search.svg`
  in the same folder adds a lightning bolt to the symbol and was not used.

### Not included

- **Zilliz Cloud:** Zilliz's [brand assets page](https://zilliz.com/brand-assets) offers the Zilliz logo
  (`https://assets.zilliz.com/zilliz-logo.zip`) and Milvus logos under its Terms and Conditions, which a download
  accepts: a limited, non-exclusive, non-transferable licence to use the assets solely to refer to, promote or create
  content about Zilliz and its products; no modification; no use that could confuse the public about the relationship
  with Zilliz; Zilliz may review uses and terminate the licence; questions to marketing@zilliz.com. Accepting those
  terms is the owner's decision, so nothing was downloaded from that page, and the Zilliz Cloud card keeps the Milvus
  type's icon.
- **Typesense:** typesense.org has no brand or press page (`/brand`, `/press`, `/media-kit`, `/brand-assets` and
  `/press-kit` return 404). Its only logo files, on the website and in its repositories, are the "typesense" wordmark
  with a small symbol, unreadable on a 32 px tile. The site's declared icon (`https://typesense.org/favicon.png`,
  620 x 620, a green bolt on a black rounded square) would be legible, but using a site icon needs the owner's
  agreement, so the Typesense cards keep their icon. Typesense® is a registered trademark of Typesense, Inc.

These SVGs retain their original geometry, colours, proportions and metadata, with no scripts, event handlers,
embedded foreign content, embedded raster images or remote references. Cards show each one with the service's plain
name on a light plate in every theme, never recoloured.
