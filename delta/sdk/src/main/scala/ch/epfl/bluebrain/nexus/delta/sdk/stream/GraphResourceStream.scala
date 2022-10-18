package ch.epfl.bluebrain.nexus.delta.sdk.stream

import ch.epfl.bluebrain.nexus.delta.kernel.database.Transactors
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.sdk.GraphResourceEncoder
import ch.epfl.bluebrain.nexus.delta.sdk.cache.KeyValueStore
import ch.epfl.bluebrain.nexus.delta.sdk.error.ServiceError.FetchContextFailed
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.FetchContext.ContextRejection
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import ch.epfl.bluebrain.nexus.delta.sourcing.state.GraphResource
import com.typesafe.scalalogging.Logger
import io.circe.{DecodingFailure, Json}
import monix.bio.{Task, UIO}

import scala.concurrent.duration._

trait GraphResourceStream {

  def apply(project: ProjectRef, tag: Tag, start: Offset): ElemStream[GraphResource]

}

object GraphResourceStream {

  private val logger: Logger = Logger[GraphResourceStream]

  // TODO make the cache configurable
  def apply(
      fetchContext: FetchContext[ContextRejection],
      qc: QueryConfig,
      xas: Transactors,
      encoders: Set[GraphResourceEncoder[_, _, _]]
  )(implicit cr: RemoteContextResolution): Task[GraphResourceStream] =
    KeyValueStore.localLRU[ProjectRef, ProjectContext](500, 2.minutes).map { kv =>
      def f(projectRef: ProjectRef): UIO[ProjectContext] = kv.getOrElseUpdate(
        projectRef,
        fetchContext
          .onRead(projectRef)
          .tapError { err =>
            Task.delay(logger.error(s"An error occured while fetching the context for project '$projectRef': $err."))
          }
          .hideErrorsWith(_ => FetchContextFailed(projectRef))
      )
      apply(f, qc, xas, encoders)
    }

  def apply(
      fetchContext: ProjectRef => UIO[ProjectContext],
      qc: QueryConfig,
      xas: Transactors,
      encoders: Set[GraphResourceEncoder[_, _, _]]
  )(implicit cr: RemoteContextResolution): GraphResourceStream = {
    val encodersMap                                                          = encoders.map { encoder => encoder.entityType -> encoder }.toMap
    def decodeValue(entityType: EntityType, json: Json): Task[GraphResource] = {
      for {
        decoder <- Task.fromEither(
                     encodersMap
                       .get(entityType)
                       .toRight(DecodingFailure(s"No decoder is available for entity type $entityType", List.empty))
                   )
        result  <- decoder.encode(json, fetchContext)
      } yield result
    }.tapError { err =>
      UIO.delay(logger.error(s"Entity of type '$entityType' could not be decoded", err))
    }

    (project: ProjectRef, tag: Tag, start: Offset) => StreamingQuery.elems(project, tag, start, qc, xas, decodeValue)
  }

}
