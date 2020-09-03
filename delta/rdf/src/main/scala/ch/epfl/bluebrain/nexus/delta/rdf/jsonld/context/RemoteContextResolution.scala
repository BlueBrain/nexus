package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextCircularDependency
import io.circe.Json
import monix.bio.IO
import org.apache.jena.iri.IRI

trait RemoteContextResolution {

  /**
    * Resolve a passed ''iri''.
    *
   * @return the expected Json payload response from the passed ''iri''
    */
  def resolve(iri: IRI): Result[Json]

  /**
    * From a given ''json'', resolve all its remote context IRIs.
    *
   * @return a Map where the keys are the IRIs resolved and the values the @context value
    *         from the payload of the resolved wrapped in an IO
    */
  final def apply(json: Json): Result[Map[IRI, Json]] = {

    def inner(ctx: Set[Json], resolved: Map[IRI, Json] = Map.empty): Result[Map[IRI, Json]] = {
      val uris: Set[IRI] = ctx.flatMap(remoteIRIs)
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

  private def remoteIRIs(ctxValue: Json): Set[IRI] =
    (ctxValue.asArray, ctxValue.as[IRI].toOption) match {
      case (Some(arr), _)    => arr.foldLeft(Set.empty[IRI]) { case (acc, c) => acc ++ c.as[IRI].toOption }
      case (_, Some(remote)) => Set(remote)
      case _                 => Set.empty[IRI]
    }
}

object RemoteContextResolution {
  type Result[A] = IO[RemoteContextResolutionError, A]

  final def apply(f: IRI => Result[Json]): RemoteContextResolution = (iri: IRI) => f(iri)
}
