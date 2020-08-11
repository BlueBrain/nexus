package ch.epfl.bluebrain.nexus.sourcingnew.projections

sealed trait ProjectionId {
  def value: String
}

final case class CacheProjectionId(value: String) extends ProjectionId

final case class ViewProjectionId(value: String) extends ProjectionId

final case class SourceProjectionId(value: String) extends ProjectionId

final case class CompositeViewProjectionId(sourceId: String, projectionId: String) extends ProjectionId {
  override def value: String = s"${sourceId}_$projectionId"
}


