package ch.epfl.bluebrain.nexus.delta.sdk.model

import java.time.Instant

import org.apache.jena.iri.IRI

/**
  * A resource representation.
  *
  * @param id         the unique identifier of the resource in a given project
  * @param rev        the revision of the resource
  * @param types      the collection of known types of this resource
  * @param deprecated whether the resource is deprecated of not
  * @param createdAt  the instant when this resource was created
  * @param createdBy  the identity that created this resource
  * @param updatedAt  the last instant when this resource was updated
  * @param updatedBy  the last identity that updated this resource
  * @param schema     the schema reference that this resource conforms to
  * @param value      the resource value
  * @tparam Id the resource id type
  * @tparam A  the resource value type
  */
final case class ResourceF[Id, A](
    id: Id,
    rev: Long,
    types: Set[IRI],
    deprecated: Boolean,
    createdAt: Instant,
    createdBy: Identity,
    updatedAt: Instant,
    updatedBy: Identity,
    schema: ResourceRef,
    value: A
) {

  /**
    * Maps the value of the resource using the supplied function f.
    *
    * @param f  the function to apply to transform the current resource value
    * @return a new resource with a value of type B
    */
  def map[B](f: A => B): ResourceF[Id, B] =
    ResourceF(id, rev, types, deprecated, createdAt, createdBy, updatedAt, updatedBy, schema, f(value))

  /**
    * Drops the resource value.
    *
   * @return a new resource with no value (just its metadata)
    */
  def unit: ResourceF[Id, Unit] =
    map(_ => ())
}
