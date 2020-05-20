package ch.epfl.bluebrain.nexus

import java.time.Instant

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.auth.Identity.Subject
import ch.epfl.bluebrain.nexus.config.AppConfig.HttpConfig
import ch.epfl.bluebrain.nexus.permissions.Permission
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.syntax.all._
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
    id: Uri,
    rev: Long,
    types: Set[Uri],
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
    * A resource metadata.
    */
  type ResourceMetadata = ResourceF[Unit]

  /**
    * Constructs a [[ResourceF]] where the value is of type Unit
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
      id: Uri,
      rev: Long,
      types: Set[Uri],
      createdAt: Instant,
      createdBy: Subject,
      updatedAt: Instant,
      updatedBy: Subject
  ): ResourceF[Unit] =
    ResourceF(id, rev, types, createdAt, createdBy, updatedAt, updatedBy, ())

  implicit val permsEncoder: Encoder[Set[Permission]] =
    Encoder.instance(perms => Json.obj("permissions" -> Json.fromValues(perms.toList.sortBy(_.value).map(_.asJson))))

  implicit def resourceFEncoder[A: Encoder](implicit http: HttpConfig): Encoder[ResourceF[A]] =
    Encoder.encodeJson.contramap { r => resourceMetaEncoder.apply(r.discard) deepMerge r.value.asJson }

  implicit def resourceMetaEncoder(implicit http: HttpConfig): Encoder[ResourceMetadata] =
    Encoder.encodeJson.contramap {
      case ResourceF(id, rev, types, createdAt, createdBy, updatedAt, updatedBy, _: Unit) =>
        // TODO: Here we are doing some JsonLD magic just using the last segment of a path to shorten the @type, consider changing
        val jsonTypes = types.toList match {
          case Nil      => Json.Null
          case t :: Nil => Json.fromString(t.path.lastSegment.getOrElse(t.toString))
          case _        => Json.arr(types.map(t => Json.fromString(t.path.lastSegment.getOrElse(t.toString))).toSeq: _*)
        }
        Json
          .obj(
            "@id"                -> id.toString.asJson,
            "@type"              -> jsonTypes,
            nxv.rev.prefix       -> Json.fromLong(rev),
            nxv.createdBy.prefix -> createdBy.id.toString.asJson,
            nxv.updatedBy.prefix -> updatedBy.id.toString.asJson,
            nxv.createdAt.prefix -> Json.fromString(createdAt.toString),
            nxv.updatedAt.prefix -> Json.fromString(updatedAt.toString)
          )
//          .addContext(iamCtxUri)
//          .addContext(resourceCtxUri)
    }
}
