package ch.epfl.bluebrain.nexus.delta

import akka.http.scaladsl.model.Uri
import org.apache.jena.iri.{IRI, IRIFactory}
import cats.implicits._

import scala.util.Try

package object rdf {

  private val iriFactory = IRIFactory.iriImplementation()

  /**
    * Attempts to construct an IRI, returning a Left when it does not have the correct IRI format.
    */
  def iri(string: String): Either[String, IRI] = {
    val iri = iriUnsafe(string)
    Option.when(!iri.hasViolation(false))(iri).toRight(s"'$string' is not an IRI")
  }

  /**
    * Attempts to construct an Uri, returning a Left when it does not have the correct Uri format.
    */
  def uri(string: String): Either[String, Uri] =
    Try(Uri(string)).toEither.leftMap(_ => s"'$string' is not an Uri")

  /**
    * Construct an IRI without checking the validity of the format.
    */
  def iriUnsafe(string: String): IRI =
    iriFactory.create(string)
}
