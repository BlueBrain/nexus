package ch.epfl.bluebrain.nexus.rdf.akka

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode
import ch.epfl.bluebrain.nexus.rdf.{Iri, Node}

/**
  * Conversions from rdf data types and Akka [[akka.http.scaladsl.model.Uri]].
  */
trait ToAkkaConverters {

  /**
    * Attempts to convert argument [[Node]] to Akka [[Uri]].
    */
  def asAkka(node: Node): Either[String, Uri] = node match {
    case IriNode(iri) => Right(asAkka(iri))
    case other        => Left(s"${other.show} cannot be converted to URI.")
  }

  /**
    * Attempts to convert argument [[AbsoluteIri]] to Akka [[Uri]].
    */
  def asAkka(iri: AbsoluteIri): Uri =
    Uri(iri.asUri)

  /**
    * Attempts to convert argument [[Iri.Path]] to Akka [[Uri.Path]]
    */
  def asAkka(path: Iri.Path): Uri.Path = Uri.Path(path.pctEncoded)
}
