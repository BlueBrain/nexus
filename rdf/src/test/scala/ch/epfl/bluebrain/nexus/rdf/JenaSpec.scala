package ch.epfl.bluebrain.nexus.rdf

import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.graph.Graph
import ch.epfl.bluebrain.nexus.rdf.iri.Iri
import ch.epfl.bluebrain.nexus.rdf.iri.Iri.Uri
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import cats.implicits._
import io.circe.Json
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.rdf.model.{AnonId, Model, ModelFactory, Property, RDFNode, Resource, ResourceFactory}
import org.apache.jena.rdf.model.{Literal => JenaLiteral}
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.riot.{Lang, RDFParser}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.datatypes.xsd.XSDDatatype.XSDstring
import scala.jdk.CollectionConverters._

import scala.util.Try

trait JenaSpec {

  def toJenaModel(g: Graph): Model = {
    def castToDatatype(lexicalText: String, dataType: Uri): Option[JenaLiteral] =
      Try {
        val typeMapper = TypeMapper.getInstance()
        val tpe        = typeMapper.getSafeTypeByName(dataType.iriString)
        val literal    = ResourceFactory.createTypedLiteral(lexicalText, tpe)
        literal.getValue //It will crash whenever the literal does not match the desired datatype
        literal
      }.toOption

    val model = ModelFactory.createDefaultModel()
    g.triples.foreach {
      case (s, p, o) =>
        val sr = s match {
          case IriNode(iri) => ResourceFactory.createResource(iri.iriString)
          case BNode(id)    => new ResourceImpl(AnonId.create(id))
        }
        val pp = ResourceFactory.createProperty(p.value.iriString)
        val or = o match {
          case Literal(lf, rdf.langString, Some(LanguageTag(tag))) =>
            ResourceFactory.createLangLiteral(lf, tag)
          case Literal(lf, dataType, _) =>
            castToDatatype(lf, dataType).getOrElse(ResourceFactory.createStringLiteral(lf))
          case IriNode(iri) => ResourceFactory.createResource(iri.iriString)
          case BNode(id)    => new ResourceImpl(AnonId.create(id))
        }
        val stmt = ResourceFactory.createStatement(sr, pp, or)
        model.add(stmt)
    }
    model
  }

  def toJenaModel(j: Json): Model = {
    val model  = ModelFactory.createDefaultModel()
    val stream = StreamRDFLib.graph(model.getGraph)
    RDFParser.create.fromString(j.noSpaces).lang(Lang.JSONLD).parse(stream)
    model
  }

  def fromJenaModel(id: Uri, model: Model): Graph = {
    def jenaToLiteral(literal: JenaLiteral): Either[String, Literal] =
      if (literal.getLanguage == null || literal.getLanguage.isEmpty)
        if (literal.getDatatype == null || literal.getDatatype == XSDstring)
          Right(Literal(literal.getLexicalForm))
        else
          Option(literal.getDatatypeURI) match {
            case Some(dataType) =>
              Iri.url(dataType).leftMap(errorMsg(dataType, _)).map(Literal(literal.getLexicalForm, _))
            case _ => Right(Literal(literal.getLexicalForm))
          }
      else
        Right(
          LanguageTag(literal.getLanguage)
            .map(Literal(literal.getLexicalForm, _))
            .getOrElse(Literal(literal.getLexicalForm))
        )

    def toIriOrBNode(resource: Resource): Either[String, IriOrBNode] =
      Option(resource.getURI) match {
        case Some(uri) if !uri.isEmpty =>
          Iri.uri(uri).map(IriNode(_)).leftMap(errorMsg(uri, _))
        case _ =>
          Right(b"${resource.getId.getLabelString}")
      }

    def propToIriNode(property: Property): Either[String, IriNode] =
      Iri.uri(property.getURI).map(IriNode(_)).leftMap(errorMsg(property.getURI, _))

    def rdfNodeToNode(rdfNode: RDFNode): Either[String, Node] =
      if (rdfNode.isLiteral)
        jenaToLiteral(rdfNode.asLiteral())
      else if (rdfNode.isAnon)
        Right(b"${rdfNode.asResource}")
      else
        Iri.uri(rdfNode.asResource.getURI).map(IriNode(_))

    def errorMsg(iriString: String, err: String): String =
      s"'$iriString' could not be converted to Iri. Reason: '$err'"

    model.listStatements().asScala.foldLeft(Graph(id)) {
      case (g, stmt) =>
        val s      = toIriOrBNode(stmt.getSubject).getOrElse(throw new IllegalArgumentException)
        val p      = propToIriNode(stmt.getPredicate).getOrElse(throw new IllegalArgumentException)
        val o      = rdfNodeToNode(stmt.getObject).getOrElse(throw new IllegalArgumentException)
        val triple = (s, p, o)
        g + triple
    }
  }
}

object JenaSpec extends JenaSpec
