package ch.epfl.bluebrain.nexus.delta.sdk.utils

import ch.epfl.bluebrain.nexus.delta.rdf.shacl.ShaclShapesGraph
import ch.epfl.bluebrain.nexus.delta.sdk.SchemaResource
import ch.epfl.bluebrain.nexus.delta.sdk.model.ResourceF
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState
import ch.epfl.bluebrain.nexus.delta.sdk.model.schemas.SchemaState.{Current, Initial}
import org.scalactic._

import scala.util.Try

trait CustomSchemasEquality {
  private def eqShapes(a: ShaclShapesGraph, b: ShaclShapesGraph): Boolean =
    a.model.isIsomorphicWith(b.model) && a.model.size() == b.model.size()

  implicit val schemaStateEquality: Equality[SchemaState] = (a: SchemaState, b: Any) =>
    (a, b) match {
      case (_: Initial, _: Initial) => true
      case (a: Current, b: Current) => a == b.copy(shapes = a.shapes) && eqShapes(a.shapes, b.shapes)
      case _                        => false
    }

  implicit val schemaResourceEquality: Equality[SchemaResource] = (a: SchemaResource, b: Any) =>
    if (b.isInstanceOf[ResourceF[_]])
      Try(b.asInstanceOf[SchemaResource]).fold(
        _ => false,
        b => b.copy(value = b.value.copy(shapes = a.value.shapes)) == a && eqShapes(a.value.shapes, b.value.shapes)
      )
    else false
}
