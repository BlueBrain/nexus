package ch.epfl.bluebrain.nexus.rdf.jsonld

import ch.epfl.bluebrain.nexus.rdf.Node.Literal
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject.NodeObjectValue.{ListValue, ValueObject}
import ch.epfl.bluebrain.nexus.rdf.jsonld.NodeObject._
import ch.epfl.bluebrain.nexus.rdf.jsonld.context.Context
import io.circe.Json

final case class NodeObject(
    id: Option[Uri] = None,
    types: Seq[Uri] = Vector.empty,
    context: Option[Context] = None,
    reverse: Seq[TermNodeObject] = Vector.empty,
    graph: Seq[NodeObject] = Vector.empty,
    included: Seq[NodeObject] = Vector.empty,
    nest: Option[Json] = None,
    index: Option[String] = None,
    terms: Seq[TermValue] = Vector.empty
) {

  lazy val emptyValues: Boolean =
    types.isEmpty && reverse.isEmpty && graph.isEmpty && included.isEmpty && nest.isEmpty && index.isEmpty && terms.isEmpty

  lazy val nonEmptyValues: Boolean =
    !emptyValues

  lazy val hasOnlyId: Boolean =
    emptyValues && id.nonEmpty

}

object NodeObject {

  type TermValue      = (Uri, NodeObjectValue)
  type TermNodeObject = (Uri, Seq[NodeObject])
  type DirectionValue = (String, Option[String])

  sealed trait ArrayEntry extends Product with Serializable

  object ArrayEntry {
    final case class NodeObjectArray(value: NodeObject) extends ArrayEntry
    final case class NodeValueArray(value: ValueObject) extends ArrayEntry
    final case class ListValueWrapper(value: ListValue) extends ArrayEntry
    implicit final def fromNodeValueArray(value: ValueObject): ArrayEntry = NodeValueArray(value)
    implicit final def fromNodeObjectArray(value: NodeObject): ArrayEntry = NodeObjectArray(value)
  }

  sealed trait NodeObjectValue extends Product with Serializable

  // format: off
  object NodeObjectValue {
    final case class WrappedNodeObject(value: NodeObject)                                                                                       extends NodeObjectValue
    final case class ValueObject(value: Literal, explicitType: Boolean = false, direction: Option[String] = None, index: Option[String] = None) extends NodeObjectValue
    final case class LanguageMap(value: Map[LanguageTag, Seq[DirectionValue]] = Map.empty, others: Seq[DirectionValue] = Vector.empty)          extends NodeObjectValue
    final case class SetValue(value: Seq[ArrayEntry] = Vector.empty, index: Option[String] = None)                                              extends NodeObjectValue
    final case class ListValue(value: Seq[ArrayEntry] = Vector.empty, index: Option[String] = None)                                             extends NodeObjectValue
    final case class TypeMap(value: Map[Uri, NodeObject] = Map.empty, others: Seq[NodeObject] = Vector.empty)                                   extends NodeObjectValue
    final case class IdMap(value: Map[Uri, Seq[NodeObject]] = Map.empty, others: Seq[NodeObject] = Vector.empty)                                extends NodeObjectValue
    final case class IndexMap(value: Map[String, NodeObjectValue] = Map.empty, others: Seq[NodeObjectValue] = Vector.empty)                     extends NodeObjectValue
    final case class JsonWrapper(value: Json)                                                                                                   extends NodeObjectValue
  }
  // format: on
}
