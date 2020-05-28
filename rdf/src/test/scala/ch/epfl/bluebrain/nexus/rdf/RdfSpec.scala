package ch.epfl.bluebrain.nexus.rdf

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8

import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.Literal.LanguageTag
import ch.epfl.bluebrain.nexus.rdf.Node.{BNode, IriNode, IriOrBNode, Literal}
import ch.epfl.bluebrain.nexus.rdf.Vocabulary.rdf
import ch.epfl.bluebrain.nexus.rdf.syntax.all._
import io.circe.Json
import io.circe.parser.parse
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.datatypes.xsd.XSDDatatype._
import org.apache.jena.rdf.model.impl.ResourceImpl
import org.apache.jena.rdf.model.{
  Model,
  ModelFactory,
  Property,
  RDFNode,
  Resource,
  Statement,
  Literal => JenaLiteral,
  _
}
import org.apache.jena.riot.system.StreamRDFLib
import org.apache.jena.riot.{Lang, RDFParser}
import org.scalactic.source
import org.scalatest.exceptions.{StackDepthException, TestFailedException}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{EitherValues, Inspectors, OptionValues, TryValues}

import scala.io.{Codec, Source}
import scala.jdk.CollectionConverters._
import scala.util.Try

trait RdfSpec extends AnyWordSpecLike with Matchers with Inspectors with EitherValues with OptionValues with TryValues {

  def urlEncode(s: String): String = URLEncoder.encode(s, UTF_8.displayName())

  class EitherValuable[L, R](either: Either[L, R], pos: source.Position) {
    def rightValue: R = either match {
      case Right(value) => value
      case Left(th: Throwable) =>
        throw new TestFailedException(
          (_: StackDepthException) => Some("The Either value is not a Right(_)"),
          Some(th),
          pos
        )
      case Left(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Right(_)"), None, pos)
    }

    def leftValue: L = either match {
      case Left(value) => value
      case Right(_) =>
        throw new TestFailedException((_: StackDepthException) => Some("The Either value is not a Left(_)"), None, pos)
    }
  }

  implicit def convertEitherToValuable[L, R](either: Either[L, R])(implicit p: source.Position): EitherValuable[L, R] =
    new EitherValuable(either, p)

  final def jsonContentOf(resourcePath: String): Json =
    parse(Source.fromInputStream(getClass.getResourceAsStream(resourcePath))(Codec.UTF8).mkString)
      .getOrElse(throw new IllegalArgumentException)

  final def jsonWithContext(resourcePath: String): Json =
    jsonContentOf("/context.json") deepMerge jsonContentOf(resourcePath)

  def toJenaModel(g: Graph): Model = {
    def castToDatatype(lexicalText: String, dataType: AbsoluteIri): Option[JenaLiteral] =
      Try {
        val typeMapper = TypeMapper.getInstance()
        val tpe        = typeMapper.getSafeTypeByName(dataType.asString)
        val literal    = ResourceFactory.createTypedLiteral(lexicalText, tpe)
        literal.getValue //It will crash whenever the literal does not match the desired datatype
        literal
      }.toOption

    val model = ModelFactory.createDefaultModel()
    g.triples.foreach {
      case (s, p, o) =>
        val sr = s match {
          case IriNode(iri) => ResourceFactory.createResource(iri.asUri)
          case BNode(id)    => new ResourceImpl(AnonId.create(id))
        }
        val pp = ResourceFactory.createProperty(p.value.asUri)
        val or = o match {
          case Literal(lf, rdf.langString, Some(LanguageTag(tag))) =>
            ResourceFactory.createLangLiteral(lf, tag)
          case Literal(lf, dataType, _) =>
            castToDatatype(lf, dataType).getOrElse(ResourceFactory.createStringLiteral(lf))
          case IriNode(iri) => ResourceFactory.createResource(iri.asUri)
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

  def fromJenaModel(id: AbsoluteIri, model: Model): Graph = {
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
          Iri.absolute(uri).map(IriNode(_)).leftMap(errorMsg(uri, _))
        case _ =>
          Right(b"${resource.getId.getLabelString}")
      }

    def propToIriNode(property: Property): Either[String, IriNode] =
      Iri.absolute(property.getURI).map(IriNode(_)).leftMap(errorMsg(property.getURI, _))

    def rdfNodeToNode(rdfNode: RDFNode): Either[String, Node] =
      if (rdfNode.isLiteral)
        jenaToLiteral(rdfNode.asLiteral())
      else if (rdfNode.isAnon)
        Right(b"${rdfNode.asResource}")
      else
        Iri.absolute(rdfNode.asResource.getURI).map(IriNode(_))

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

  def toString(stmt: Statement): String = {
    s"${stmt.getSubject.toString} ${stmt.getPredicate.toString} ${stmt.getObject.toString} ."
  }

}
