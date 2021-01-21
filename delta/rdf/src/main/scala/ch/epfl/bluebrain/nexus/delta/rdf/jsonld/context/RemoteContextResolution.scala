package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError.{InvalidJson, ResourcePathNotFound}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.{RemoteContextNotFound, RemoteContextWrongPayload}
import io.circe.Json
import monix.bio.IO

trait RemoteContextResolution { self =>

  /**
    * Resolve a passed ''iri''.
    *
    * @return the expected Json payload response from the passed ''iri''
    */
  def resolve(iri: Iri): Result[Json]

  /**
    * From a given ''json'', resolve all its remote context IRIs.
    *
    * @return a Map where the keys are the IRIs resolved and the values the @context value
    *         from the payload of the resolved wrapped in an IO
    */
  final def apply(json: Json): Result[Map[Iri, ContextValue]] = {

    def inner(ctx: Set[ContextValue], resolved: Map[Iri, ContextValue] = Map.empty): Result[Map[Iri, ContextValue]] = {
      val uris: Set[Iri] = ctx.flatMap(remoteIRIs).diff(resolved.keySet)
      for {
        curResolved     <- IO.parTraverseUnordered(uris)(uri => resolve(uri).map(j => uri -> j.topContextValueOrEmpty))
        curResolvedMap   = curResolved.toMap
        accResolved      = curResolvedMap ++ resolved
        recurseResolved <- IO.parTraverseUnordered(curResolvedMap.values)(json => inner(Set(json), accResolved))
      } yield recurseResolved.foldLeft(accResolved)(_ ++ _)
    }

    inner(json.contextValues)
  }

  private def remoteIRIs(ctxValue: ContextValue): Set[Iri] =
    ctxValue match {
      case ContextArray(vector)  => vector.collect { case ContextRemoteIri(uri) => uri }.toSet
      case ContextRemoteIri(uri) => Set(uri)
      case _                     => Set.empty
    }

  /**
    * Merges the current [[RemoteContextResolution]] with the passed ones
    */
  def merge(others: RemoteContextResolution*): RemoteContextResolution =
    new RemoteContextResolution {
      override def resolve(iri: Iri): Result[Json] = {
        val tasks = self.resolve(iri) :: others.map(_.resolve(iri)).toList
        IO.tailRecM(tasks) {
          case Nil          => IO.raiseError(RemoteContextNotFound(iri)) // that never happens
          case head :: Nil  => head.map(Right.apply)
          case head :: tail => head.map(Right.apply).onErrorFallbackTo(IO.pure(Left(tail)))
        }
      }
    }
}

object RemoteContextResolution {
  type Result[A] = IO[RemoteContextResolutionError, A]

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f a pair of [[Iri]] and the resolved Result of [[Json]]
    */
  final def fixedIO(f: (Iri, Result[Json])*): RemoteContextResolution = new RemoteContextResolution {
    private val map = f.toMap

    override def resolve(iri: Iri): Result[Json] =
      map.get(iri) match {
        case Some(result) => result
        case None         => IO.raiseError(RemoteContextNotFound(iri))
      }
  }

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f a pair of [[Iri]] and the resolved [[Json]] or a [[ClasspathResourceError]]
    */
  final def fixedIOResource(f: (Iri, IO[ClasspathResourceError, Json])*): RemoteContextResolution =
    fixedIO(f.map { case (iri, io) =>
      iri -> io.mapError {
        case _: InvalidJson          => RemoteContextWrongPayload(iri)
        case _: ResourcePathNotFound => RemoteContextNotFound(iri)
      }
    }: _*)

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f a pair of [[Iri]] and the resolved [[Json]]
    */
  final def fixed(f: (Iri, Json)*): RemoteContextResolution =
    fixedIO(f.map { case (iri, json) => iri -> IO.pure(json) }: _*)

  /**
    * A remote context resolution that never resolves
    */
  final val never: RemoteContextResolution                  = new RemoteContextResolution {
    override def resolve(iri: Iri): Result[Json] = IO.raiseError(RemoteContextNotFound(iri))
  }
}
