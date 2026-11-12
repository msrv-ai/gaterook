-- A connector module (docs/connector-delivery.md) is one of GateRook's own connectors delivered apart from the
-- installer. It is installed, updated and rolled back as a plugin is and lives in the same table. `module` tells the
-- two apart, because a module's connectors are trusted as the compiled-in ones are and a plugin's are not. A module's
-- manifest column holds its own manifest with every connector type it adds. The row MAC covers the flag and the
-- manifest only where the flag is set, so the rows written before this column keep their MACs.
ALTER TABLE plugin ADD COLUMN module BOOLEAN NOT NULL DEFAULT FALSE;
