package ch.epfl.bluebrain.nexus.delta.sdk.migration

object MigrationState {

  def isRunning: Boolean =
    sys.env.getOrElse("MIGRATE_DATA", "false").toBooleanOption.getOrElse(false)

  def isIndexingDisabled: Boolean =
    sys.env.getOrElse("DISABLE_INDEXING", "false").toBooleanOption.getOrElse(false)

}
