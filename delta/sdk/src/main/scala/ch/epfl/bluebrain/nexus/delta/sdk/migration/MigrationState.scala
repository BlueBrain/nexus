package ch.epfl.bluebrain.nexus.delta.sdk.migration

object MigrationState {

  def isRunning: Boolean = sys.env.isDefinedAt("MIGRATE_DATA")

  def isIndexingDisabled: Boolean = sys.env.isDefinedAt("DISABLE_INDEXING")

}
