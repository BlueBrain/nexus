package ch.epfl.bluebrain.nexus.kg.indexing

import cats.Functor
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import io.circe.{Encoder, Json}
import io.circe.syntax._

final case class IdentifiedProgress[A](sourceId: Option[AbsoluteIri], projectionId: Option[AbsoluteIri], value: A)

object IdentifiedProgress {

  def apply[A](sourceId: AbsoluteIri, projectionId: AbsoluteIri, value: A): IdentifiedProgress[A] =
    IdentifiedProgress(Some(sourceId), Some(projectionId), value)

  def apply[A](sourceId: AbsoluteIri, value: A): IdentifiedProgress[A] =
    IdentifiedProgress(Some(sourceId), None, value)

  def apply[A](value: A): IdentifiedProgress[A] =
    IdentifiedProgress(None, None, value)

  private def optionalIdJson(prefix: String, value: Option[AbsoluteIri]): Json =
    value.map(id => Json.obj(prefix -> id.asString.asJson)).getOrElse(Json.obj())

  implicit val functorProgress: Functor[IdentifiedProgress]                    = new Functor[IdentifiedProgress] {
    override def map[A, B](fa: IdentifiedProgress[A])(f: A => B): IdentifiedProgress[B] = fa.copy(value = f(fa.value))
  }

  implicit def encoderProgressIdentifiedValue[A: Encoder]: Encoder[IdentifiedProgress[A]] =
    Encoder.instance {
      case IdentifiedProgress(sourceId, projectionId, value) =>
        optionalIdJson("sourceId", sourceId) deepMerge optionalIdJson(
          "projectionId",
          projectionId
        ) deepMerge value.asJson
    }

  implicit def orderingIdentifiedProgress[A]: Ordering[IdentifiedProgress[A]] =
    (x: IdentifiedProgress[A], y: IdentifiedProgress[A]) => {
      val sourceId1     = x.sourceId.map(_.asString).getOrElse("")
      val sourceId2     = y.sourceId.map(_.asString).getOrElse("")
      val projectionId1 = x.projectionId.map(_.asString).getOrElse("")
      val projectionId2 = y.projectionId.map(_.asString).getOrElse("")
      s"$sourceId1$projectionId1" compareTo s"$sourceId2$projectionId2"
    }
}
