package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ResourceRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json
import monix.bio.{IO, UIO}

/**
  * Contract definition for registering and consuming the ability to retrieve resources in a common JSON-LD format.
  *
  * Plugins can provide implementations for custom resource types such that those resources can be handled in an uniform
  * way.
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
    * @param project
    *   the resource parent project
    * @param reference
    *   the resource reference
    * @return
    *   some value if the reference is defined for this instance, none otherwise
    */
  def fetch(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]]
}

object ReferenceExchange {

  /**
    * A successful result of a [[ReferenceExchange]] presenting means for retrieving the resource in one of the common
    * formats. An instance of this value asserts the existence of the resource (toResource and toSource are strict
    * values).
    *
    * @param resource
    *   returns the resource value with its metadata
    * @param source
    *   returns the recorded source value
    * @param encoder
    *   returns the JsonLdEncoder for the type [[A]] for transforming the resource in a desired JSONLD format
    * @tparam A
    *   the value type of resource
    */
  final case class ReferenceExchangeValue[A](resource: ResourceF[A], source: Json, encoder: JsonLdEncoder[A]) {
    def jsonLdValue(implicit base: BaseUri): JsonLdValue = {
      implicit val e: JsonLdEncoder[A] = encoder
      JsonLdValue(resource)
    }
  }

  def apply[B](fetchResource: (ResourceRef, ProjectRef) => IO[_, ResourceF[B]], extractSource: B => Json)(implicit
      encoder: JsonLdEncoder[B]
  ): ReferenceExchange = new ReferenceExchange {

    override type A = B

    def fetch(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A]]] =
      resourceToValue(fetchResource(reference, project))

    private def resourceToValue(resourceIO: IO[_, ResourceF[A]]): UIO[Option[ReferenceExchangeValue[A]]] =
      resourceIO
        .map(res => Some(ReferenceExchangeValue(res, extractSource(res.value), encoder)))
        .onErrorHandle(_ => None)
  }
}
