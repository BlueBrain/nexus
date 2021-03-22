package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{ResourceF, ResourceRef}
import io.circe.Json
import monix.bio.UIO

/**
  * Contract definition for registering and consuming the ability to retrieve resources in a common JSON-LD format.
  *
  * Plugins can provide implementations for custom resource types such that those resources can be handled in an
  * uniform way.
  *
  * Examples of use-cases would be: the ability to register resources defined by plugins (e.g. views)
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
  def toResource(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]]

  /**
    * Exchange a reference for the resource constrained by a specific schema in common formats.
    *
    * @param project   the resource parent project
    * @param schema    the reference to the schema that constrains the resource
    * @param reference the resource reference
    * @return some value if the reference is defined for this instance, none otherwise
    */
  def toResource(
      project: ProjectRef,
      schema: ResourceRef,
      reference: ResourceRef
  ): UIO[Option[ReferenceExchangeValue[A]]]
}

object ReferenceExchange {

  type Aux[A0] = ReferenceExchange { type A = A0 }

  /**
    * A successful result of a [[ReferenceExchange]] presenting means for retrieving the resource in one of the
    * common formats. An instance of this value asserts the existence of the resource (toResource and toSource are
    * strict values).
    *
    * @param toResource returns the resource value with its metadata
    * @param toSource   returns the recorded source value
    * @param encoder    returns the JsonLdEncoder for the type [[A]] for transforming the resource in a desired JSONLD format
    * @tparam A the value type of resource
    */
  final case class ReferenceExchangeValue[A](toResource: ResourceF[A], toSource: Json, encoder: JsonLdEncoder[A])
}
