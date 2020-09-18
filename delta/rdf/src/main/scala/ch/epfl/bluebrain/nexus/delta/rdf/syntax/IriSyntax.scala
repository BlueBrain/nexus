package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf._
import org.apache.jena.iri.IRI

trait IriSyntax {
  implicit final def iriStringContextSyntax(sc: StringContext): IriStringContextOps = new IriStringContextOps(sc)
  implicit final def iriStringSyntax(string: String): IriStringOps                  = new IriStringOps(string)
  implicit final def iriSyntax(iri: IRI): IriOpts                                   = new IriOpts(iri)
}

final class IriStringContextOps(private val sc: StringContext) extends AnyVal {

  /**
    * Construct an IRI without checking the validity of the format.
    */
  def iri(args: Any*): IRI = iriUnsafe(sc.s(args: _*))
}

final class IriStringOps(private val string: String) extends AnyVal {

  /**
    * Attempts to construct an IRI, returning a Left when it does not have the correct IRI format.
    */
  def toIri: Either[String, IRI] = iri(string)
}

final class IriOpts(private val iri: IRI) extends AnyVal {

  /**
    * @return true if the current ''iri'' starts with the passed ''other'' iri, false otherwise
    */
  def startsWith(other: IRI): Boolean =
    iri.toString.startsWith(other.toString)

  /**
    * @return the resulting string from stripping the passed ''iri'' to the current iri.
    */
  def stripPrefix(iri: IRI): String =
    stripPrefix(iri.toString)

  /**
    * @return the resulting string from stripping the passed ''prefix'' to the current iri.
    */
  def stripPrefix(prefix: String): String =
    iri.toString.stripPrefix(prefix)

  /**
   * An IRI is a prefix mapping if it ends with `/` or `#`
   */
  def isPrefixMapping: Boolean =
    iri.toString.endsWith("/") || iri.toString.endsWith("#")
}
