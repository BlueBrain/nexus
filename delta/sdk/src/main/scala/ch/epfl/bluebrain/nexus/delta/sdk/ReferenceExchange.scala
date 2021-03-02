package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.RdfError
import ch.epfl.bluebrain.nexus.delta.rdf.graph.{Dot, NTriples}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.{CompactedJsonLd, ExpandedJsonLd}
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import io.circe.Json
import monix.bio.{IO, UIO}

/**
  * Contract definition for registering and consuming the ability to retrieve resources in a common JSON-LD format.
  * Plugins can provide implementations for custom resource types such that those resources can be handled in an
  * uniform way. Examples of use-cases would be: the ability to register resources defined by plugins (e.g. views) to
  * be included in archives or being able to implement a generic `GET /resources/{org}/{proj}/_/{id}` endpoint.
  */
trait ReferenceExchange {

  /**
    * The resource value type.
    */
  type A

  /**
    * Exchange a reference for the resource in common formats.
    *
    * @param project   the resource parent project
    * @param reference the resource reference
    * @return some value if the reference is defined for this instance, none otherwise
    */
  def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]]

  /**
    * Exchange a reference for the resource constrained by a specific schema in common formats.
    *
    * @param project   the resource parent project
    * @param schema    the reference to the schema that constrains the resource
    * @param reference the resource reference
    * @return some value if the reference is defined for this instance, none otherwise
    */
  def apply(project: ProjectRef, schema: ResourceRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]]
}

object ReferenceExchange {

  /**
    * A successful result of a [[ReferenceExchange]] presenting functions for retrieving the resource in one of the
    * common formats. An instance of this value asserts the existence of the resource (toResource and toSource are
    * strict values).
    *
    * @param toResource  returns the resource value with its metadata
    * @param toSource    returns the recorded source value
    * @param toCompacted returns the resource in its compacted json-ld representation
    * @param toExpanded  returns the resource in its expanded json-ld representation
    * @param toNTriples  returns the resource in its n-triples representation
    * @param toDot       returns the resource in its dot representation
    * @tparam A the value type of resource
    */
  final class ReferenceExchangeValue[A](
      val toResource: ResourceF[A],
      val toSource: Json,
      val toCompacted: IO[RdfError, CompactedJsonLd],
      val toExpanded: IO[RdfError, ExpandedJsonLd],
      val toNTriples: IO[RdfError, NTriples],
      val toDot: IO[RdfError, Dot]
  )
}
