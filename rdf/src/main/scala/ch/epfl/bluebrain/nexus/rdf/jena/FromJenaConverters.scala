package ch.epfl.bluebrain.nexus.rdf.jena

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Graph.Triple
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.xsd
import ch.epfl.bluebrain.nexus.rdf.{Graph, Iri, Node}
import org.apache.jena.rdf.model
import org.apache.jena.rdf.model.{Model, RDFNode, Resource}

import scala.jdk.CollectionConverters._
import scala.util.Try

/**
  * Conversions from Jena to rdf data types.
  */
@SuppressWarnings(Array("NullParameter"))
trait FromJenaConverters {

  /**
    * Converts the argument `model` to a `Graph` using the `node` as the root node.
    */
  def asRdfGraph(node: Node, model: Model): Either[String, Graph] = {
    val triples = model.listStatements().asScala.foldLeft[Either[String, Set[Triple]]](Right(Set.empty)) {
      case (acc, st) =>
        for {
          set    <- acc
          s      <- asRdfIriOrBNode(st.getSubject)
          p      <- asRdfIriNode(st.getPredicate)
          o      <- asRdfNode(st.getObject)
          triple = (s, p, o)
        } yield set + triple
    }
    triples.map(set => Graph(node, set))
  }

  /**
    * Converts the argument Jena `literal` to an rdf `Literal`.
    */
  def asRdfLiteral(literal: model.Literal): Either[String, Literal] = {
    val dataType = {
      val dt = literal.getDatatypeURI
      if (dt == null || dt.isEmpty) Right(xsd.string)
      else Iri.absolute(dt)
    }
    val languageTag = {
      val lt = literal.getLanguage
      if (lt == null || lt.isEmpty) Right(None)
      else LanguageTag(lt).map(Some(_))
    }
    val lexicalForm = {
      val lf = literal.getLexicalForm
      if (lf == null) Left("The lexical form of the literal is null")
      else Right(lf)
    }
    for {
      lf <- lexicalForm
      dt <- dataType
      lt <- languageTag
    } yield Literal(lf, dt, lt)
  }

  /**
    * Converts the argument Jena `resource` to an rdf `IriNode` if the resource is an IRI.
    */
  def asRdfIriNode(resource: Resource): Either[String, IriNode] = {
    val uri = resource.getURI
    if (uri == null || uri.isEmpty) Left("The resource is not an IRI")
    else Iri.absolute(uri).map(IriNode(_))
  }

  /**
    * Converts the argument Jena `resource` to an rdf `BNode` if the resource is a blank node.
    */
  def asRdfBNode(resource: Resource): Either[String, BNode] =
    Try(resource.getId.getBlankNodeId.getLabelString).toEither
      .leftMap(_ => "The resource is not a blank node")
      .flatMap(BNode(_))

  /**
    * Converts the argument Jena `resource` to an rdf `IriOrBNode`.
    */
  def asRdfIriOrBNode(resource: Resource): Either[String, IriOrBNode] =
    if (resource.isAnon) asRdfBNode(resource)
    else asRdfIriNode(resource)

  /**
    * Converts the argument Jena `node` to an rdf `Node`.
    */
  def asRdfNode(node: RDFNode): Either[String, Node] =
    if (node.isLiteral) asRdfLiteral(node.asLiteral())
    else if (node.isAnon) asRdfBNode(node.asResource())
    else asRdfIriNode(node.asResource())

}
