package ch.epfl.bluebrain.nexus.delta

import org.apache.jena.iri.{IRI, IRIFactory}

package object rdf {

  private val iriFactory = IRIFactory.iriImplementation()

  /**
    * Attempts to construct an IRI, returning Left(InvalidIri) when the iri does not have the correct format.
    */
  def iri(string: String): Either[String, IRI] = {
    val iri = iriUnsafe(string)
    Option.when(!iri.hasViolation(false))(iri).toRight(s"'$string' is not an IRI")
  }

  /**
    * Construct an IRI without checking the validity of the format.
    */
  def iriUnsafe(string: String): IRI =
    iriFactory.create(string)
}
