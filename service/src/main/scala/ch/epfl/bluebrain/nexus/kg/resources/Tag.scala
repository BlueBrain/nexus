package ch.epfl.bluebrain.nexus.kg.resources

import cats.implicits._
import ch.epfl.bluebrain.nexus.kg.config.Contexts._
import ch.epfl.bluebrain.nexus.kg.config.Vocabulary.nxv
import ch.epfl.bluebrain.nexus.kg.resources.Rejection.InvalidResourceFormat
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.implicits._
import ch.epfl.bluebrain.nexus.rdf.{GraphDecoder, NonEmptyString}
import io.circe.{Encoder, Json}

/**
  * Represents a tag
  * @param rev the tag revision
  * @param value the tag name
  */
final case class Tag(rev: Long, value: String)

object Tag {

  /**
    * Attempts to constructs a Tag from the provided json
    *
    * @param resId the resource identifier
    * @param json  the payload
    * @return Right(tag) when successful and Left(rejection) when failed
    */
  final def apply(resId: ResId, json: Json): Either[Rejection, Tag] = {
    val completeJson = (json deepMerge tagCtx).id(resId.value)
    for {
      g <- completeJson.toGraph(resId.value).leftMap(_ => InvalidResourceFormat(resId.ref, "Empty or wrong Json-LD."))
      t <- tagGraphDecoder(g.cursor).leftRejectionFor(resId.ref)
    } yield t
  }

  // format: on

  implicit final val tagEncoder: Encoder[Tag] = Encoder.instance {
    case Tag(rev, tag) => Json.obj(nxv.tag.prefix -> Json.fromString(tag), "rev" -> Json.fromLong(rev))
  }

  implicit final val tagGraphDecoder: GraphDecoder[Tag] = GraphDecoder.instance { c =>
    for {
      rev <- c.down(nxv.rev).as[Long]
      tag <- c.down(nxv.tag).as[NonEmptyString].map(_.asString)
    } yield Tag(rev, tag)
  }
}
