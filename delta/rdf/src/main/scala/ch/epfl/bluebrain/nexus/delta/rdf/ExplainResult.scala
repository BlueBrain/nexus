package ch.epfl.bluebrain.nexus.delta.rdf

import cats.effect.IO
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext

/**
  * Gives additional information besides the result of the Json-Ld operation
  * @param remoteContexts
  *   the remote contexts that have been resolved
  * @param value
  *   the result of the operation
  */
final case class ExplainResult[A](remoteContexts: Map[Iri, RemoteContext], value: A) {

  def as[B](newValue: B): ExplainResult[B] =
    copy(value = newValue)
  def map[B](f: A => B): ExplainResult[B]  =
    copy(value = f(value))

  def evalMap[B](f: A => IO[B]): IO[ExplainResult[B]] =
    f(value).map { b => copy(value = b) }
}
