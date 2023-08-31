package ch.epfl.bluebrain.nexus.delta.rdf

import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext
import monix.bio.IO

final case class ExplainResult[A](remoteContexts: Map[Iri, RemoteContext], value: A) {

  def as[B](newValue: B): ExplainResult[B] =
    copy(value = newValue)
  def map[B](f: A => B): ExplainResult[B]  =
    copy(value = f(value))

  def evalMap[E, B](f: A => IO[E, B]): IO[E, ExplainResult[B]] =
    f(value).map { b => copy(value = b) }
}
