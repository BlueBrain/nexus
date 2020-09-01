package ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context

import akka.http.scaladsl.model.Uri
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolutionError.RemoteContextCircularDependency
import io.circe.Json
import monix.bio.IO
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution.Result

trait RemoteContextResolution {

  /**
    * Resolve a passed ''uri''.
    *
   * @return the expected Json payload response from the passed ''uri''
    */
  def resolve(uri: Uri): Result[Json]

  /**
    * From a given ''json'', resolve all its remote context Uris.
    *
   * @return a Map where the keys are the Uris resolved and the values the @context value
    *         from the payload of the resolved wrapped in an IO
    */
  final def apply(json: Json): Result[Map[Uri, Json]] = {

    def inner(ctx: Set[Json], resolved: Map[Uri, Json] = Map.empty): Result[Map[Uri, Json]] = {
      val uris: Set[Uri] = ctx.flatMap(remoteUris)
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

  private def remoteUris(ctxValue: Json): Set[Uri] =
    (ctxValue.asArray, ctxValue.as[Uri].toOption) match {
      case (Some(arr), _)    => arr.foldLeft(Set.empty[Uri]) { case (acc, c) => acc ++ c.as[Uri].toOption }
      case (_, Some(remote)) => Set(remote)
      case _                 => Set.empty[Uri]
    }
}

object RemoteContextResolution {
  type Result[A] = IO[RemoteContextResolutionError, A]

  final def apply(f: Uri => Result[Json]): RemoteContextResolution = (uri: Uri) => f(uri)
}
