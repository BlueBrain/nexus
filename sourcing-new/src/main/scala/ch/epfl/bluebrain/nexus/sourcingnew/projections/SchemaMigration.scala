package ch.epfl.bluebrain.nexus.sourcingnew.projections

/**
  * Component which allows to apply the migration scripts
  * on the database
  *
  * @tparam F
  */
trait SchemaMigration[F[_]] {

  /**
    * Apply the migration
    * @return
    */
  def migrate(): F[Unit]

}
