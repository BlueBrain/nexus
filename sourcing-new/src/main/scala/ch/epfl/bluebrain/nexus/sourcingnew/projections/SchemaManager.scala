package ch.epfl.bluebrain.nexus.sourcingnew.projections

trait SchemaManager[F[_]] {

  def migrate(): F[Unit]

}
