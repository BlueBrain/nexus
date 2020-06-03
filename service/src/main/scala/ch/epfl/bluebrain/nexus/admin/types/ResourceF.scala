package ch.epfl.bluebrain.nexus.admin.types

import java.time.Instant
import java.util.UUID

import akka.cluster.ddata.LWWRegister.Clock
import ch.epfl.bluebrain.nexus.admin.config.Contexts._
import ch.epfl.bluebrain.nexus.admin.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.commons.search.QueryResults.UnscoredQueryResults
import ch.epfl.bluebrain.nexus.iam.client.config.IamClientConfig
import ch.epfl.bluebrain.nexus.iam.client.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import io.circe.syntax._
import io.circe.{Encoder, Json}

/**
  * The metadata information for any resource in the service
  *
  * @param id         the id of the resource
  * @param uuid       the permanent internal identifier of the resource
  * @param rev        the revision
  * @param deprecated the deprecation status
  * @param types      the types of the resource
  * @param createdAt  the creation date of the resource
  * @param createdBy  the identity that created the resource
  * @param updatedAt  the last update date of the resource
  * @param updatedBy  the identity that performed the last update to the resource
  * @param value      the resource value
  */
final case class ResourceF[A](
    id: AbsoluteIri,
    uuid: UUID,
    rev: Long,
    deprecated: Boolean,
    types: Set[AbsoluteIri],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject,
    value: A
) {

  /**
    * Creates a new [[ResourceF]] changing the value using the provided ''v'' argument.
    *
    * @param v the new value.
    * @tparam B the generic type of the resulting value field
    */
  def withValue[B](v: B): ResourceF[B] = copy(value = v)

  /**
    * Converts the current [[ResourceF]] to a [[ResourceF]] where the value is of type Unit.
    */
  def discard: ResourceF[Unit] = copy(value = ())
}

object ResourceF {

  /**
    * Constructs a [[ResourceF]] where the value is of type Unit
    *
    * @param id         the identifier of the resource
    * @param uuid       the permanent internal identifier of the resource
    * @param rev        the revision of the resource
    * @param deprecated the deprecation status
    * @param types      the types of the resource
    * @param createdAt  the instant when the resource was created
    * @param createdBy  the identity that created the resource
    * @param updatedAt  the instant when the resource was updated
    * @param updatedBy  the identity that updated the resource
    */
  def unit(
      id: AbsoluteIri,
      uuid: UUID,
      rev: Long,
      deprecated: Boolean,
      types: Set[AbsoluteIri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ): ResourceF[Unit] =
    ResourceF(id, uuid, rev, deprecated, types, createdAt, createdBy, updatedAt, updatedBy, ())

  implicit def resourceMetaEncoder(implicit iamClientConfig: IamClientConfig): Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap {
      case ResourceF(id, uuid, rev, deprecated, types, createdAt, createdBy, updatedAt, updatedBy, _: Unit) =>
        val jsonTypes = types.toList match {
          case Nil      => Json.Null
          case t :: Nil => Json.fromString(t.lastSegment.getOrElse(t.asString))
          case _        => Json.arr(types.map(t => Json.fromString(t.lastSegment.getOrElse(t.asString))).toSeq: _*)
        }
        Json.obj(
          "@context"            -> Json.arr(adminCtxUri.asJson, resourceCtxUri.asJson),
          "@id"                 -> id.asJson,
          "@type"               -> jsonTypes,
          nxv.uuid.prefix       -> Json.fromString(uuid.toString),
          nxv.rev.prefix        -> Json.fromLong(rev),
          nxv.deprecated.prefix -> Json.fromBoolean(deprecated),
          nxv.createdBy.prefix  -> createdBy.id.asJson,
          nxv.updatedBy.prefix  -> updatedBy.id.asJson,
          nxv.createdAt.prefix  -> Json.fromString(createdAt.toString),
          nxv.updatedAt.prefix  -> Json.fromString(updatedAt.toString)
        )
    }

  implicit def resourceEncoder[A: Encoder](implicit iamClientConfig: IamClientConfig): Encoder[ResourceF[A]] =
    Encoder.encodeJson.contramap { resource =>
      resource.discard.asJson.deepMerge(resource.value.asJson)
    }

  implicit def uqrsEncoder[A: Encoder](
      implicit iamClientConfig: IamClientConfig
  ): Encoder[UnscoredQueryResults[ResourceF[A]]] = {
    Encoder.encodeJson.contramap {
      case UnscoredQueryResults(total, results, _) =>
        Json.obj(
          "@context"         -> Json.arr(adminCtxUri.asJson, resourceCtxUri.asJson, searchCtxUri.asJson),
          nxv.total.prefix   -> Json.fromLong(total),
          nxv.results.prefix -> Json.arr(results.map(_.source.asJson.removeKeys("@context")): _*)
        )
    }
  }

  implicit def clock[A]: Clock[ResourceF[A]] = { (_: Long, value: ResourceF[A]) =>
    value.rev
  }

  private implicit class AbsoluteIriSyntax(private val iri: AbsoluteIri) extends AnyVal {
    def lastSegment: Option[String] =
      iri.path.head match {
        case segment: String => Some(segment)
        case _               => None
      }
  }
}
