package ai.senscience.nexus.delta.plugins.graph.analytics.indexing

import ai.senscience.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.{Index, Noop, UpdateByQuery}
import ai.senscience.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.all.*
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceState
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits.*
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.{ElemStreaming, SelectFilter}
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.ElemStream
import ch.epfl.bluebrain.nexus.delta.sourcing.{Scope, Transactors}
import doobie.*
import doobie.syntax.all.*
import io.circe.Json

trait GraphAnalyticsStream {

  /**
    * Allows to generate a non-terminating [[GraphAnalyticsResult]] stream for the given project
    * @param project
    *   the project to stream from
    * @param start
    *   the offset to start with
    */
  def apply(project: ProjectRef, start: Offset): ElemStream[GraphAnalyticsResult]

}

object GraphAnalyticsStream {

  private val emptyMapType = Map.empty[Iri, Set[Iri]]

  /**
    * We look for a resource with these ids in this project and return their types if it can be found
    */
  private def query(project: ProjectRef, xas: Transactors)(nel: NonEmptyList[Iri]): IO[Map[Iri, Set[Iri]]] = {
    val inIds = Fragments.in(fr"id", nel)
    sql"""
         | SELECT id, value->'types'
         | FROM public.scoped_states
         | WHERE org = ${project.organization}
         | AND project = ${project.project}
         | AND $inIds
         | AND tag = ${Tag.latest.value}
         |""".stripMargin
      .query[(Iri, Option[Json])]
      .to[List]
      .transact(xas.streaming)
      .map { l =>
        l.foldLeft(emptyMapType) { case (acc, (id, json)) =>
          val types = json.flatMap(_.as[Set[Iri]].toOption).getOrElse(Set.empty)
          acc + (id -> types)
        }
      }
  }

  /**
    * We batch ids and looks for existing resources
    */
  private[indexing] def findRelationships(project: ProjectRef, xas: Transactors, batchSize: Int)(
      ids: Set[Iri]
  ): IO[Map[Iri, Set[Iri]]] = {
    val groupIds = NonEmptyList.fromList(ids.toList).map(_.grouped(batchSize).toList)
    val noop     = IO.pure(emptyMapType)
    groupIds.fold(noop) { list =>
      list.foldLeftM(emptyMapType) { case (acc, l) =>
        query(project, xas)(l).map(_ ++ acc)
      }
    }
  }

  // $COVERAGE-OFF$
  def apply(elemStreaming: ElemStreaming, xas: Transactors): GraphAnalyticsStream =
    (project: ProjectRef, start: Offset) => {

      // This seems a reasonable value to batch relationship resolution for resources with a lot
      // of references
      val relationshipBatch = 500

      // Decode the json payloads to [[GraphAnalyticsResult]] We only care for resources and files
      def decode(entityType: EntityType, json: Json): IO[GraphAnalyticsResult] =
        entityType match {
          case Files.entityType     =>
            IO.fromEither(FileState.serializer.codec.decodeJson(json)).map { s =>
              UpdateByQuery(s.id, s.types)
            }
          case Resources.entityType =>
            IO.fromEither(ResourceState.serializer.codec.decodeJson(json)).flatMap {
              case state if state.deprecated => deprecatedIndex(state)
              case state                     =>
                JsonLdDocument.fromExpanded(state.expanded, findRelationships(project, xas, relationshipBatch)).map {
                  doc => activeIndex(state, doc)
                }
            }
          case _                    => IO.pure(Noop)
        }

      elemStreaming(Scope(project), start, SelectFilter.latest, decode)
    }
  // $COVERAGE-ON$

  private def deprecatedIndex(state: ResourceState) =
    IO.pure(
      Index.deprecated(
        state.project,
        state.id,
        state.remoteContexts,
        state.rev,
        state.types,
        state.createdAt,
        state.createdBy,
        state.updatedAt,
        state.updatedBy
      )
    )

  private def activeIndex(state: ResourceState, doc: JsonLdDocument) =
    Index.active(
      state.project,
      state.id,
      state.remoteContexts,
      state.rev,
      state.types,
      state.createdAt,
      state.createdBy,
      state.updatedAt,
      state.updatedBy,
      doc
    )

}
