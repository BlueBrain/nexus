package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.{RemoteContextCircularDependency, RemoteContextNotFound}
import io.circe.Json
import monix.bio.IO

trait RemoteContextResolution {

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
      val uris: Set[Iri] = ctx.flatMap(remoteIRIs)
      for {
        _               <- IO.fromEither(uris.find(resolved.keySet.contains).toLeft(uris).leftMap(RemoteContextCircularDependency))
        curResolved     <- IO.parTraverseUnordered(uris)(uri => resolve(uri).map(j => uri -> j.topContextValueOrEmpty))
        curResolvedMap   = curResolved.toMap
        accResolved      = curResolvedMap ++ resolved
        recurseResolved <- IO.parTraverseUnordered(curResolvedMap.values)(json => inner(Set(json), accResolved))
      } yield recurseResolved.foldLeft(accResolved)(_ ++ _)
    }

    inner(json.contextValues)
  }

  private def remoteIRIs(ctxValue: ContextValue): Set[Iri] =
    (ctxValue.value.asArray, ctxValue.value.as[Iri].toOption) match {
      case (Some(arr), _)    => arr.foldLeft(Set.empty[Iri]) { case (acc, c) => acc ++ c.as[Iri].toOption }
      case (_, Some(remote)) => Set(remote)
      case _                 => Set.empty[Iri]
    }
}

object RemoteContextResolution {
  type Result[A] = IO[RemoteContextResolutionError, A]

  /**
    * Helper method to construct a [[RemoteContextResolution]] .
    *
    * @param f a function from an [[Iri]] to a [[Result]] of [[Json]]
    */
  final def fixed(f: (Iri, Json)*): RemoteContextResolution =
    new RemoteContextResolution {
      private val map = f.toMap

      override def resolve(iri: Iri): Result[Json] =
        map.get(iri) match {
          case Some(result) => IO.pure(result)
          case None         => IO.raiseError(RemoteContextNotFound(iri))
        }
    }
}
