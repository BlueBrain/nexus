package ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing

import cats.data.NonEmptyList
import cats.syntax.all._
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.indexing.GraphAnalyticsResult.{Index, Noop, UpdateByQuery}
import ch.epfl.bluebrain.nexus.delta.plugins.graph.analytics.model.JsonLdDocument
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.Files
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileState
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceState
import ch.epfl.bluebrain.nexus.delta.sourcing.Transactors
import ch.epfl.bluebrain.nexus.delta.sourcing.config.QueryConfig
import ch.epfl.bluebrain.nexus.delta.sourcing.implicits._
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{ElemStream, EntityType, ProjectRef, Tag}
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.query.StreamingQuery
import doobie._
import doobie.implicits._
import io.circe.Json
import monix.bio.{Task, UIO}

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
  private def query(project: ProjectRef, xas: Transactors)(nel: NonEmptyList[Iri]): UIO[Map[Iri, Set[Iri]]] = {
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
  }.hideErrors

  /**
    * We batch ids and looks for existing resources
    */
  private[indexing] def findRelationships(project: ProjectRef, xas: Transactors, batchSize: Int)(
      ids: Set[Iri]
  ): UIO[Map[Iri, Set[Iri]]] = {
    val groupIds = NonEmptyList.fromList(ids.toList).map(_.grouped(batchSize).toList)
    val noop     = UIO.pure(emptyMapType)
    groupIds.fold(noop) { list =>
      list.foldLeftM(emptyMapType) { case (acc, l) =>
        query(project, xas)(l).map(_ ++ acc)
      }
    }
  }

  // $COVERAGE-OFF$
  def apply(qc: QueryConfig, xas: Transactors): GraphAnalyticsStream = (project: ProjectRef, start: Offset) => {

    // This seems a reasonable value to batch relationship resolution for resources with a lot
    // of references
    val relationshipBatch = 500

    // Decode the json payloads to [[GraphAnalyticsResult]] We only care for resources and files
    def decode(entityType: EntityType, json: Json): Task[GraphAnalyticsResult] =
      entityType match {
        case Files.entityType     =>
          Task.fromEither(FileState.serializer.codec.decodeJson(json)).map { s =>
            UpdateByQuery(s.id, s.types)
          }
        case Resources.entityType =>
          Task.fromEither(ResourceState.serializer.codec.decodeJson(json)).flatMap {
            case s if s.deprecated =>
              Task.pure(
                Index.deprecated(s.project, s.id, s.rev, s.types, s.createdAt, s.createdBy, s.updatedAt, s.updatedBy)
              )
            case s                 =>
              JsonLdDocument.fromExpanded(s.expanded, findRelationships(project, xas, relationshipBatch)).map { d =>
                Index.active(s.project, s.id, s.rev, s.types, s.createdAt, s.createdBy, s.updatedAt, s.updatedBy, d)
              }
          }
        case _                    => Task.pure(Noop)
      }

    StreamingQuery.elems(project, Tag.latest, start, qc, xas, decode)
  }
  // $COVERAGE-ON$
}
