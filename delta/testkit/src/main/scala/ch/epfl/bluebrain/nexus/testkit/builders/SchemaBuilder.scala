package ch.epfl.bluebrain.nexus.testkit.builders

import ch.epfl.bluebrain.nexus.testkit.builders.SchemaBuilder.State
import ch.epfl.bluebrain.nexus.testkit.builders.SchemaBuilder.State.{Empty, HasId, HasShape, Valid}
import io.circe.Json
import io.circe.syntax.KeyOps
import shapeless.<:!<

import scala.annotation.unused

object SchemaBuilder {
  sealed trait State

  def schemaWith: SchemaBuilder[Empty] = {
    SchemaBuilder()
  }

  object State {
    sealed trait Empty    extends State
    sealed trait HasShape extends State
    sealed trait HasId    extends State

    type Valid = Empty with HasShape
  }
}

case class SchemaBuilder[A <: State] private (
    id: Option[String] = None,
    prefixes: Map[String, String] = Map.empty,
    imports: List[String] = Nil,
    shapes: List[Json] = Nil
) {
  def prefixForNamespace(prefix: String, namespace: String): SchemaBuilder[A] = {
    this.copy(prefixes = this.prefixes + (prefix -> namespace))
  }

  def shape(shape: Json): SchemaBuilder[A with HasShape] = {
    this.copy(shapes = this.shapes :+ shape)
  }

  def id(id: String)(implicit @unused ev: A <:!< HasId): SchemaBuilder[A with HasId] = {
    this.copy(id = Some(id))
  }

  def build(implicit ev: A <:< Valid): Json = {

    val idField = id.map("@id" := _)

    val contextsField = Option.when(prefixes.nonEmpty) {
      "@context" := Json.fromFields(
        prefixes.map { case (name, url) => name := url }
      )
    }

    val importsField = Option.when(imports.nonEmpty) {
      "imports" := imports
    }

    val shapesField = Option.when(shapes.nonEmpty) {
      "shapes" := shapes
    }

    Json.fromFields(
      idField ++ contextsField ++ importsField ++ shapesField
    )
  }
}
