package ch.epfl.bluebrain.nexus.rdf.akka

import akka.http.scaladsl.model.Uri
import ch.epfl.bluebrain.nexus.rdf.Iri
import ch.epfl.bluebrain.nexus.rdf.Iri.{AbsoluteIri, Path}
import ch.epfl.bluebrain.nexus.rdf.Iri.Path.{Segment, Slash}
import ch.epfl.bluebrain.nexus.rdf.Node.IriNode

import scala.annotation.tailrec

/**
  * Conversions from Akka [[akka.http.scaladsl.model.Uri]] and  rdf data types .
  */
trait FromAkkaConverters {

  /**
    * Converts Akka [[Uri]] to [[AbsoluteIri]]
    */
  def asAbsoluteIri(uri: Uri): AbsoluteIri = AbsoluteIri.unsafe(uri.toString)

  /**
    * Converts Akka [[Uri]] to [[IriNode]]
    */
  def asRdfIriNode(uri: Uri): IriNode = IriNode(AbsoluteIri.unsafe(uri.toString))

  /**
    * Converts Akka [[Uri.Path]] to [[Iri.Path]]
    */
  def asIriPath(path: Uri.Path): Iri.Path = {
    @tailrec
    def inner(acc: Iri.Path, remaining: Uri.Path): Iri.Path = remaining match {
      case Uri.Path.SingleSlash         => Slash(acc)
      case Uri.Path.Empty               => acc
      case Uri.Path.Slash(tail)         => inner(Slash(acc), tail)
      case Uri.Path.Segment(head, tail) => inner(Segment(head, acc), tail)
    }
    inner(Path.Empty, path)
  }
}
