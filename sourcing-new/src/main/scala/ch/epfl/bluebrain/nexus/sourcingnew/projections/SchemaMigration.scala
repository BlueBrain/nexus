package ch.epfl.bluebrain.nexus.sourcingnew.projections

trait SchemaMigration[F[_]] {

  def migrate(): F[Unit]

}
