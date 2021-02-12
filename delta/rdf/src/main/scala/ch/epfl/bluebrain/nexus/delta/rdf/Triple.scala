package ch.epfl.bluebrain.nexus.delta.rdf

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.rdf.model._
import org.apache.jena.rdf.model.impl.ResourceImpl

import java.text.{DecimalFormat, DecimalFormatSymbols}
import java.time.format.DateTimeFormatter
import java.time.{Instant, ZoneOffset}
import java.util.Locale
import scala.util.Try

// $COVERAGE-OFF$
object Triple {

  /**
    * An RDF triple.
    * Subject must be an Iri or a blank node.
    * Predicate must be an Iri.
    * Object must be an Iri, a blank node or a Literal.
    */
  type Triple = (Resource, Property, RDFNode)

  private val eFormatter = new DecimalFormat("0.###############E0", new DecimalFormatSymbols(Locale.ENGLISH))
  eFormatter.setMinimumFractionDigits(1)

  def subject(value: IriOrBNode): Resource =
    value match {
      case Iri(iri)             => ResourceFactory.createResource(iri.toString)
      case IriOrBNode.BNode(id) => new ResourceImpl(AnonId.create(id))
    }

  def bNode: Resource =
    ResourceFactory.createResource()

  def predicate(value: Iri): Property =
    ResourceFactory.createProperty(value.toString)

  def predicate(value: Uri): Property =
    ResourceFactory.createProperty(value.toString)

  def obj(value: String, lang: Option[String] = None): RDFNode =
    lang.fold(ResourceFactory.createPlainLiteral(value))(l => ResourceFactory.createLangLiteral(value, l))

  def obj(value: String, dataType: Option[Iri], languageTag: Option[String]): RDFNode =
    dataType match {
      case Some(dt) =>
        Try {
          val tpe     = TypeMapper.getInstance().getSafeTypeByName(dt.toString)
          val literal = ResourceFactory.createTypedLiteral(value, tpe)
          literal.getValue // It will throw whenever the literal does not match the desired datatype
          literal
        }.getOrElse(obj(value, languageTag))
      case None     => obj(value, languageTag)

    }

  def obj(value: Boolean): RDFNode =
    ResourceFactory.createTypedLiteral(value.toString, XSDDatatype.XSDboolean)

  def obj(value: Int): RDFNode =
    ResourceFactory.createTypedLiteral(value.toString, XSDDatatype.XSDinteger)

  def obj(value: Long): RDFNode =
    ResourceFactory.createTypedLiteral(value.toString, XSDDatatype.XSDinteger)

  def obj(value: Double): RDFNode =
    ResourceFactory.createTypedLiteral(eFormatter.format(value), XSDDatatype.XSDdouble)

  def obj(value: Float): RDFNode =
    obj(value.toDouble)

  def obj(value: Instant): RDFNode = ResourceFactory.createTypedLiteral(
    value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT),
    XSDDatatype.XSDdateTime
  )

  def obj(value: IriOrBNode): RDFNode =
    subject(value)

  final def apply(stmt: Statement): Triple =
    (stmt.getSubject, stmt.getPredicate, stmt.getObject)

}
// $COVERAGE-ON$
