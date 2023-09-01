package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError
import ch.epfl.bluebrain.nexus.delta.kernel.utils.ClasspathResourceError._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.ContextValue._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.{RemoteContextNotFound, RemoteContextWrongPayload}
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContext.StaticContext
import io.circe.Json
import monix.bio.IO

trait RemoteContextResolution { self =>

  /**
    * Resolve a passed ''iri''.
    *
    * @return
    *   the expected Json payload response from the passed ''iri''
    */
  def resolve(iri: Iri): Result[RemoteContext]

  /**
    * From a given ''json'', resolve all its remote context IRIs.
    *
    * @return
    *   a Map where the keys are the IRIs resolved and the values the @context value from the payload of the resolved
    *   wrapped in an IO
    */
  final def apply(json: Json): Result[Map[Iri, RemoteContext]] = {

    def inner(
        ctx: Set[ContextValue],
        resolved: Map[Iri, RemoteContext] = Map.empty
    ): Result[Map[Iri, RemoteContext]] = {
      val uris: Set[Iri] = ctx.flatMap(remoteIRIs).diff(resolved.keySet)
      for {
        curResolved     <- IO.parTraverseUnordered(uris)(uri => resolve(uri).map(uri -> _))
        curResolvedMap   = curResolved.toMap
        accResolved      = curResolvedMap ++ resolved
        recurseResolved <-
          IO.parTraverseUnordered(curResolvedMap.values)(context => inner(Set(context.value), accResolved))
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
    (iri: Iri) => {
      val tasks = self.resolve(iri) :: others.map(_.resolve(iri)).toList
      IO.tailRecM(tasks) {
        case Nil          => IO.raiseError(RemoteContextNotFound(iri)) // that never happens
        case head :: Nil  => head.map(Right.apply)
        case head :: tail => head.map(Right.apply).onErrorFallbackTo(IO.pure(Left(tail)))
      }
    }
}

object RemoteContextResolution {
  type Result[A] = IO[RemoteContextResolutionError, A]

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f
    *   a pair of [[Iri]] and the resolved Result of [[ContextValue]]
    */
  final def fixedIO(f: (Iri, Result[ContextValue])*): RemoteContextResolution = new RemoteContextResolution {
    private val map = f.toMap

    override def resolve(iri: Iri): Result[RemoteContext] =
      map.get(iri) match {
        case Some(result) => result.map { value => StaticContext(iri, value) }
        case None         => IO.raiseError(RemoteContextNotFound(iri))
      }
  }

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f
    *   a pair of [[Iri]] and the resolved [[ContextValue]] or a [[ClasspathResourceError]]
    */
  final def fixedIOResource(f: (Iri, IO[ClasspathResourceError, ContextValue])*): RemoteContextResolution =
    fixedIO(f.map { case (iri, io) =>
      iri -> io.memoizeOnSuccess.mapError {
        case _: InvalidJson | _: InvalidJsonObject => RemoteContextWrongPayload(iri)
        case _: ResourcePathNotFound               => RemoteContextNotFound(iri)
      }
    }: _*)

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f
    *   a pair of [[Iri]] and the resolved [[ContextValue]]
    */
  final def fixed(f: (Iri, ContextValue)*): RemoteContextResolution =
    fixedIO(f.map { case (iri, json) => iri -> IO.pure(json) }: _*)

  /**
    * A remote context resolution that never resolves
    */
  final val never: RemoteContextResolution                          = (iri: Iri) => IO.raiseError(RemoteContextNotFound(iri))
}
