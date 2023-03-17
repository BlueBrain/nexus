package ch.epfl.bluebrain.nexus.testkit.builders

import ch.epfl.bluebrain.nexus.testkit.builders.JsonBuilders.optionalField
import ch.epfl.bluebrain.nexus.testkit.builders.ShapeBuilder.State
import ch.epfl.bluebrain.nexus.testkit.builders.ShapeBuilder.State._
import io.circe.Json
import io.circe.syntax.KeyOps
import shapeless.<:!<

import scala.annotation.unused

object ShapeBuilder {

  def shapeWith(id: String, path: String, typ: String, name: String): ShapeBuilder[Empty] = {
    ShapeBuilder[Empty](id, path, typ, name)
  }

  sealed trait State
  object State {
    sealed trait Empty           extends State
    sealed trait HasTargetClass  extends State
    sealed trait HasMaxInclusive extends State
    sealed trait HasDatatype     extends State

    type Valid = Empty with HasTargetClass
  }
}

case class ShapeBuilder[A <: State] private (
    id: String,
    path: String,
    typ: String,
    name: String,
    targetClasses: List[String] = Nil,
    datatype: Option[String] = None,
    maxInclusive: Option[Int] = None
) {
  def build(implicit ev: A <:< Valid): Json = {
    Json.fromFields(
      List(
        "@id"   := id,
        "@type" := typ,
        "path"  := path,
        "name"  := name
      ) ++ optionalField("targetClass", targetClasses) ++ optionalField("datatype", datatype) ++ optionalField(
        "maxInclusive",
        maxInclusive
      )
    )
  }

  def datatype(datatype: String)(implicit @unused ev: A <:!< HasDatatype): ShapeBuilder[A with HasDatatype] = {
    this.copy(datatype = Some(datatype))
  }

  def maxInclusive(amount: Int)(implicit @unused ev: A <:!< HasMaxInclusive): ShapeBuilder[A with HasMaxInclusive] = {
    this.copy(maxInclusive = Some(amount))
  }

  def targetClass(targetClass: String): ShapeBuilder[A with HasTargetClass] = {
    this.copy(targetClasses = this.targetClasses :+ targetClass)
  }
}
