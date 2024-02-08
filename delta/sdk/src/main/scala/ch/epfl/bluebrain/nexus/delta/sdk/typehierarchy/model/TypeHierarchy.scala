package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import io.circe.Encoder
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.deriveConfiguredEncoder

import scala.annotation.nowarn

/**
  * A type hierarchy representation.
  * @param mapping
  *   type hierarchy mapping
  */
case class TypeHierarchy(mapping: TypeHierarchyMapping)

object TypeHierarchy {
  type TypeHierarchyMapping = Map[Iri, Set[Iri]]

  @nowarn("cat=unused")
  implicit private val config: Configuration = Configuration.default

  implicit val typeHierarchyEncoder: Encoder.AsObject[TypeHierarchy] =
    deriveConfiguredEncoder[TypeHierarchy]
}
