package ch.epfl.bluebrain.nexus.delta.sdk

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.encoder.JsonLdEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.ReferenceExchange.ReferenceExchangeValue
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sdk.model.{Event, ResourceF, ResourceRef, TagLabel}
import io.circe.Json
import monix.bio.UIO

/**
  * Contract definition for registering and consuming the ability to retrieve resources in a common JSON-LD format using
  * either a scoped versioned id ([[ResourceRef]] and [[ProjectRef]]) or an [[Event]].
  *
  * Plugins can provide implementations for custom resource types such that those resources can be handled in an
  * uniform way.
  *
  * Examples of use-cases would be: the ability to index resources while replaying a log of events of different types,
  * the ability to register resources defined by plugins (e.g. views) to be included in archives or being able to
  * implement a generic `GET /resources/{org}/{proj}/_/{id}` endpoint.
  */
trait ReferenceExchange {

  /**
    * The event type.
    */
  type E <: Event

  /**
    * The resource value type.
    */
  type A

  /**
    * The resource metadata value type.
    */
  type M

  /**
    * Exchange a reference for the resource in common formats.
    *
    * @param project   the resource parent project
    * @param reference the resource reference
    * @return some value if the reference is defined for this instance, none otherwise
    */
  def apply(project: ProjectRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A, M]]]

  /**
    * Exchange a reference for the resource constrained by a specific schema in common formats.
    *
    * @param project   the resource parent project
    * @param schema    the reference to the schema that constrains the resource
    * @param reference the resource reference
    * @return some value if the reference is defined for this instance, none otherwise
    */
  def apply(project: ProjectRef, schema: ResourceRef, reference: ResourceRef): UIO[Option[ReferenceExchangeValue[A, M]]]

  /**
    * Exchange an event with the corresponding resource identifier and its scope.
    *
    * @param event the event to exchange
    * @return an optional tuple of the resource parent [[ProjectRef]] and resource id [[Iri]]
    */
  def apply(event: Event): Option[(ProjectRef, Iri)]

  /**
    * Exchange an event and an optional tag with the corresponding [[ReferenceExchangeValue]].
    *
    * @param event the event to exchange
    * @param tag   an optional tag for the resource that will be used for collecting a specific resource revision
    * @return an optional [[ReferenceExchangeValue]] representing the resource at its latest revision or a specific one
    */
  def apply(event: Event, tag: Option[TagLabel]): UIO[Option[ReferenceExchangeValue[A, M]]] =
    apply(event) match {
      case Some((project, iri)) =>
        tag match {
          case Some(value) => apply(project, ResourceRef.Tag(iri, value))
          case None        => apply(project, ResourceRef.Latest(iri))
        }
      case None                 => UIO.pure(None)
    }
}

object ReferenceExchange {

  /**
    * A successful result of a [[ReferenceExchange]] presenting means for retrieving the resource in one of the
    * common formats. An instance of this value asserts the existence of the resource (toResource and toSource are
    * strict values).
    *
    * @param toResource        returns the resource value with its metadata
    * @param toSource          returns the recorded source value
    * @param metadataExtractor the function to extract the metadata from the value [[A]]
    * @param encoder           returns the JsonLdEncoder for the type [[A]] for transforming the resource in a desired JSONLD
    * @param metadataEncoder           returns the JsonLdEncoder for the type [[M]] for transforming the resource in a desired JSONLD
    *                          format
    * @tparam A the value type of resource
    * @tparam M the value type of resource metadata
    */
  final class ReferenceExchangeValue[A, M](
      val toResource: ResourceF[A],
      val toSource: Json,
      metadataExtractor: A => M
  )(implicit val encoder: JsonLdEncoder[A], val metadataEncoder: JsonLdEncoder[M]) {
    def toResourceMetadata: ResourceF[M] = toResource.map(metadataExtractor)
  }

  object ReferenceExchangeValue {

    /**
      * Constructs a [[ReferenceExchangeValue]] of [[A]] from its [[ResourceF]] and source [[Json]] representation.
      *
      * @param resource          the resource value with its metadata
      * @param source            the source json
      * @param metadataExtractor the function to extract the metadata from the value [[M]]
      */
    def apply[A: JsonLdEncoder, M: JsonLdEncoder](resource: ResourceF[A], source: Json)(
        metadataExtractor: A => M
    ): ReferenceExchangeValue[A, M] =
      new ReferenceExchangeValue[A, M](resource, source, metadataExtractor)
  }
}
