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
