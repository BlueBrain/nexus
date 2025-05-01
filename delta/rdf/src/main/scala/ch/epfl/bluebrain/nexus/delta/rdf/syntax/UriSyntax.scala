package ch.epfl.bluebrain.nexus.delta.rdf.syntax

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import org.http4s.Uri

trait UriSyntax extends org.http4s.syntax.LiteralsSyntax {
  implicit final def uriSyntax(uri: Uri): UriOps         = new UriOps(uri)
  implicit final def pathSyntax(path: Uri.Path): PathOps = new PathOps(path)
}

final class UriOps(private val uri: Uri) extends AnyVal {

  /**
    * Constructs an [[Iri]] from a [[Uri]]
    */
  def toIri: Iri = Iri.unsafe(uri.toString)

  /**
    * Add a final slash to the uri
    */
  def finalSlash: Uri = uri.withPath(uri.path.addEndsWithSlash)
}

final class PathOps(private val path: Uri.Path) extends AnyVal {

  /**
    * @return
    *   a path last segment
    */
  def lastSegment: Option[String] = path.segments.lastOption.map(_.toString)
}
