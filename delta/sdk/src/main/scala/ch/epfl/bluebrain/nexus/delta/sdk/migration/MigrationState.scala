package ch.epfl.bluebrain.nexus.delta.sdk.migration

object MigrationState {

  def isRunning: Boolean =
    sys.env.getOrElse("MIGRATE_DATA", "false").toBooleanOption.getOrElse(false)

  def isCheck: Boolean =
    sys.env.getOrElse("MIGRATE_CHECK", "false").toBooleanOption.getOrElse(false)

  private def isIndexingDisabled: Boolean =
    sys.env.getOrElse("DISABLE_INDEXING", "false").toBooleanOption.getOrElse(false)

  def isEsIndexingDisabled: Boolean =
    sys.env.getOrElse("DISABLE_ES_INDEXING", "false").toBooleanOption.getOrElse(false) || isIndexingDisabled

  def isBgIndexingDisabled: Boolean =
    sys.env.getOrElse("DISABLE_BG_INDEXING", "false").toBooleanOption.getOrElse(false) || isIndexingDisabled

  def isCompositeIndexingDisabled: Boolean =
    sys.env.getOrElse("DISABLE_COMPOSITE_INDEXING", "false").toBooleanOption.getOrElse(false) || isIndexingDisabled

}
