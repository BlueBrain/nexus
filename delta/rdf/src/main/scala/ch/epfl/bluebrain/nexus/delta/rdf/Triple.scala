package ch.epfl.bluebrain.nexus.delta.rdf

import java.text.{DecimalFormat, DecimalFormatSymbols}
import java.util.Locale

import org.apache.jena.datatypes.xsd.XSDDatatype
import org.apache.jena.iri.IRI
import org.apache.jena.rdf.model._

object Triple {

  /**
    * An RDF triple.
    * Subject must be an IRI or a blank node.
    * Predicate must be an IRI.
    * Object must be an IRI, a blank node or a Literal.
    */
  type Triple = (Resource, Property, RDFNode)

  private val eFormatter = new DecimalFormat("0.###############E0", new DecimalFormatSymbols(Locale.ENGLISH))
  eFormatter.setMinimumFractionDigits(1)

  def subject(value: IRI): Resource =
    ResourceFactory.createResource(value.toString)

  def bNode: Resource =
    ResourceFactory.createResource()

  def predicate(value: IRI): Property =
    ResourceFactory.createProperty(value.toString)

  def obj(value: String, lang: Option[String] = None): RDFNode =
    lang.fold(ResourceFactory.createPlainLiteral(value))(l => ResourceFactory.createLangLiteral(value, l))

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

  def obj(value: IRI): RDFNode =
    subject(value)

  final def apply(stmt: Statement): Triple =
    (stmt.getSubject, stmt.getPredicate, stmt.getObject)

}
