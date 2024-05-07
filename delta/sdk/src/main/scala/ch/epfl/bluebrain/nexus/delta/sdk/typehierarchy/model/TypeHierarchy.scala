package ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.Vocabulary.contexts
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.typehierarchy.model.TypeHierarchy.TypeHierarchyMapping
import io.circe.{Decoder, Encoder}
import io.circe.generic.extras.Configuration
import io.circe.generic.extras.semiauto.{deriveConfiguredDecoder, deriveConfiguredEncoder}

/**
  * A type hierarchy representation.
  * @param mapping
  *   type hierarchy mapping
  */
case class TypeHierarchy(mapping: TypeHierarchyMapping)

object TypeHierarchy {
  type TypeHierarchyMapping = Map[Iri, Set[Iri]]

  implicit private val config: Configuration = Configuration.default

  implicit val typeHierarchyMappingDecoder: Decoder[TypeHierarchy] =
    deriveConfiguredDecoder[TypeHierarchy]

  implicit val typeHierarchyEncoder: Encoder.AsObject[TypeHierarchy] =
    deriveConfiguredEncoder[TypeHierarchy]

  val context: ContextValue = ContextValue(contexts.typeHierarchy)

  implicit val typeHierarchyJsonLdEncoder: JsonLdEncoder[TypeHierarchy] =
    JsonLdEncoder.computeFromCirce(context)
}
