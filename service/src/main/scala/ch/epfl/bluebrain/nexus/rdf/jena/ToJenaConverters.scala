package ch.epfl.bluebrain.nexus.rdf.jena

import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.{Graph, Node}
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.rdf.model
import org.apache.jena.rdf.model._
import org.apache.jena.rdf.model.impl.ResourceImpl

import scala.util.Try

/**
  * Conversions from rdf to Jena to data types.
  */
trait ToJenaConverters {

  /**
    * Converts the argument `node` to a Jena `RDFNode`.
    */
  def asJena(node: Node): RDFNode = node match {
    case in: IriNode  => asJena(in)
    case bn: BNode    => asJena(bn)
    case lit: Literal => asJena(lit)
  }

  /**
    * Converts the argument `node` to a Jena `Resource`.
    */
  def asJena(node: IriOrBNode): Resource = node match {
    case in: IriNode => asJena(in)
    case bn: BNode   => asJena(bn)
  }

  /**
    * Converts the argument `literal` to a Jena `Literal`.
    */
  def asJena(literal: Literal): model.Literal = literal match {
    case Literal(lf, _, Some(LanguageTag(value))) => ResourceFactory.createLangLiteral(lf, value)
    case Literal(lf, dt, _) =>
      Try {
        val tpe     = TypeMapper.getInstance().getSafeTypeByName(dt.asString)
        val literal = ResourceFactory.createTypedLiteral(lf, tpe)
        literal.getValue // It will throw whenever the literal does not match the desired datatype
        literal
      }.getOrElse(ResourceFactory.createStringLiteral(lf))
  }

  /**
    * Converts the argument blank `node` to a Jena `Resource`.
    */
  def asJena(node: BNode): Resource =
    new ResourceImpl(AnonId.create(node.id))

  /**
    * Converts the argument `node` to a Jena `Property`.
    */
  def asJena(node: IriNode): Property =
    ResourceFactory.createProperty(node.value.asString)

  /**
    * Converts the argument `graph` to a Jena mutable `Model`.
    */
  def asJena(graph: Graph): Model = {
    val model = ModelFactory.createDefaultModel()
    graph.triples.foreach {
      case (s, p, o) =>
        model.add(asJena(s), asJena(p), asJena(o))
    }
    model
  }
}
