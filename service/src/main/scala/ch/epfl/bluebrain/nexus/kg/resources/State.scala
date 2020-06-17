package ch.epfl.bluebrain.nexus.kg.resources

import java.time.Instant

import cats.Applicative
import cats.data.EitherT
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier.ProjectRef
import ch.epfl.bluebrain.nexus.kg.resources.file.File.FileAttributes
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.Json

/**
  * Enumeration of resource states.
  */
sealed trait State extends Product with Serializable {

  /**
    * @return the resource if not in the initial state, None otherwise
    */
  def asResource: Option[Resource]

  /**
    * @param ifInitial the rejection to return if the this is ''Initial''
    * @return the resource in an F context or the ''ifInitial'' rejection otherwise
    */
  def resource[F[_]: Applicative](ifInitial: => Rejection): F[Either[Rejection, Resource]] =
    resourceT[F](ifInitial).value

  /**
    * @param ifInitial the rejection to return if the this is ''Initial''
    * @return the resource in an F context or the ''ifInitial'' rejection otherwise
    */
  def resourceT[F[_]: Applicative](ifInitial: => Rejection): EitherT[F, Rejection, Resource] =
    EitherT.fromOption[F](asResource, ifInitial)
}

object State {

  /**
    * The initial (undefined) state.
    */
  sealed trait Initial extends State

  /**
    * The initial (undefined) state.
    */
  final case object Initial extends Initial {
    override val asResource: Option[Resource] = None
  }

  /**
    * An existing resource state.
    *
    * @param id           the resource identifier
    * @param organization the organization resource identifier
    * @param rev          the resource revision
    * @param types        the collection of known resource types
    * @param deprecated   whether the resource is deprecated or not
    * @param tags         the collection of resource tags
    * @param file         the optional file attributes with the storage reference
    * @param created      the instant when the resource was created
    * @param updated      the instant when the resource was last updated
    * @param createdBy    the subject that created the resource
    * @param updatedBy    the subject that last updated the resource
    * @param schema       the schema reference that constrains this resource
    * @param source       the source representation of the resource
    */
  final case class Current(
      id: Id[ProjectRef],
      organization: OrganizationRef,
      rev: Long,
      types: Set[AbsoluteIri],
      deprecated: Boolean,
      tags: Map[String, Long],
      file: Option[(StorageReference, FileAttributes)],
      created: Instant,
      updated: Instant,
      createdBy: Subject,
      updatedBy: Subject,
      schema: Ref,
      source: Json
  ) extends State {

    /**
      * The resource counterpart.
      */
    lazy val toResource: Resource =
      ResourceF(id, rev, types, deprecated, tags, file, created, updated, createdBy, updatedBy, schema, source)

    override lazy val asResource: Option[Resource] =
      Some(toResource)
  }
}
