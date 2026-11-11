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

- **Oracle Database:** Oracle's [logo guidelines](https://www.oracle.com/legal/logos/)
  require Oracle's written authorisation to use the Oracle logo or the O Tag,
  and its brand guidelines say downloading the artwork needs an Oracle
  account. That sign-in was not used, and oracle.com refused the automated
  request (HTTP 403). Oracle publishes no Oracle Database product icon; the
  [OCI diagram toolkit](https://docs.oracle.com/en-us/iaas/Content/General/Reference/graphicsfordiagrams.htm)
  draws its service icons as native PowerPoint shapes and offers no SVG.
  Requests go to trademar_us@oracle.com with a written summary and mock-up.
- **Google Cloud:** the press kit offers only the "Google Cloud" wordmark lockup, unreadable on a 32 px tile, and
  no symbol-only file, so the GCP connector type keeps its icon.

These SVGs also retain their original geometry, colours, proportions and metadata, with no scripts, event handlers,
embedded foreign content, embedded raster images or remote asset references. Cards show each one with the service's
plain name on a light plate in every theme, never recoloured.
