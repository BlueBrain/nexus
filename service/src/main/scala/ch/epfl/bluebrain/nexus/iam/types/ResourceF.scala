package ch.epfl.bluebrain.nexus.iam.types

import java.time.Instant

import ch.epfl.bluebrain.nexus.iam.syntax._
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.service.config.Contexts._
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.HttpConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import io.circe.syntax._
import io.circe.{Encoder, Json}

/**
  * The metadata information for any resource in the service
  *
  * @param id         the id of the resource
  * @param rev        the revision
  * @param types      the types of the resource
  * @param createdAt  the creation date of the resource
  * @param createdBy  the subject that created the resource
  * @param updatedAt  the last update date of the resource
  * @param updatedBy  the subject that performed the last update to the resource
  * @param value      the resource value
  */
final case class ResourceF[A](
    id: AbsoluteIri,
    rev: Long,
    types: Set[AbsoluteIri],
    createdAt: Instant,
    createdBy: Subject,
    updatedAt: Instant,
    updatedBy: Subject,
    value: A
) {

  /**
    * Creates a new [[ResourceF]] changing the value using the provided ''f'' function.
    *
    * @param f a function to convert the current value
    * @tparam B the generic type of the resulting value field
    */
  def map[B](f: A => B): ResourceF[B] =
    copy(value = f(value))

  /**
    * Converts the current [[ResourceF]] to a [[ResourceF]] where the value is of type Unit.
    */
  def discard: ResourceF[Unit] =
    map(_ => ())
}

object ResourceF {

  /**
    * Constrcuts a [[ResourceF]] where the value is of type Unit
    *
    * @param id         the identifier of the resource
    * @param rev        the revision of the resource
    * @param types      the types of the resource
    * @param createdAt  the instant when the resource was created
    * @param createdBy  the subject that created the resource
    * @param updatedAt  the instant when the resource was updated
    * @param updatedBy  the subject that updated the resource
    */
  def unit(
      id: AbsoluteIri,
      rev: Long,
      types: Set[AbsoluteIri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ): ResourceF[Unit] =
    ResourceF(id, rev, types, createdAt, createdBy, updatedAt, updatedBy, ())

  implicit val permsEncoder: Encoder[Set[Permission]]                                         =
    Encoder.instance(perms => Json.obj("permissions" -> Json.fromValues(perms.toList.sortBy(_.value).map(_.asJson))))

  implicit def resourceFEncoder[A: Encoder](implicit http: HttpConfig): Encoder[ResourceF[A]] =
    Encoder.encodeJson.contramap { r => resourceMetaEncoder.apply(r.discard) deepMerge r.value.asJson }

  implicit def resourceMetaEncoder(implicit http: HttpConfig): Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap {
      case ResourceF(id, rev, types, createdAt, createdBy, updatedAt, updatedBy, _: Unit) =>
        val jsonTypes = types.toList match {
          case Nil      => Json.Null
          case t :: Nil => Json.fromString(t.lastSegment.getOrElse(t.asString))
          case _        => Json.arr(types.map(t => Json.fromString(t.lastSegment.getOrElse(t.asString))).toSeq: _*)
        }
        Json
          .obj(
            "@id"                -> id.asJson,
            "@type"              -> jsonTypes,
            nxv.rev.prefix       -> Json.fromLong(rev),
            nxv.createdBy.prefix -> createdBy.id.asJson,
            nxv.updatedBy.prefix -> updatedBy.id.asJson,
            nxv.createdAt.prefix -> Json.fromString(createdAt.toString),
            nxv.updatedAt.prefix -> Json.fromString(updatedAt.toString)
          )
          .addContext(iamCtxUri)
          .addContext(resourceCtxUri)
    }
}
