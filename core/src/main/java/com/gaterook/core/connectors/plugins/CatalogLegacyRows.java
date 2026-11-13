package com.gaterook.core.connectors.plugins;

import com.gaterook.core.platform.LegacyRows;
import org.springframework.stereotype.Component;

/**
 * The catalog's stored state from before its settings were signed (V87): the upgrade signs the last good index as the
 * older version kept it and records its sequence as the highest one accepted ({@link PluginCatalog#signLegacyRows}),
 * so the Catalog keeps its cache and its rollback protection. A database this start creates has no catalog yet.
 */
@Component
class CatalogLegacyRows implements LegacyRows {

  private final PluginCatalog catalog;

  CatalogLegacyRows(PluginCatalog catalog) {
    this.catalog = catalog;
  }

  @Override
  public String migration() {
    return "87";
  }

  @Override
  public String signLegacyRows() {
    return catalog.signLegacyRows();
  }
}
