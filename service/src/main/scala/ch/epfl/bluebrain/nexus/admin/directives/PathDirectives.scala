package ch.epfl.bluebrain.nexus.admin.directives

import java.util.UUID

import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Directives._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Try

object PathDirectives {

  /**
    * Extracts the organization segment and converts it to UUID.
    * If the conversion is possible, it attempts to fetch the organization from the cache in order to retrieve the label. Otherwise it returns the fetched segment
    */
  def org(implicit cache: OrganizationCache[Task], s: Scheduler): Directive1[String] =
    pathPrefix(Segment).flatMap { segment =>
      Try(UUID.fromString(segment))
        .map(
          uuid =>
            onSuccess(cache.get(uuid).runToFuture).map {
              case Some(resource) => resource.value.label
              case None           => segment
            }
        )
        .getOrElse(provide(segment))
    }

  /**
    * Extracts the organization and project segments and converts them to UUIDs.
    * If the conversion is possible, it attempts to fetch the project from the cache in order to retrieve the labels. Otherwise it returns the fetched segments
    */
  def project(implicit cache: ProjectCache[Task], s: Scheduler): Directive1[(String, String)] =
    pathPrefix(Segment / Segment).tflatMap {
      case (orgSegment, projSegment) =>
        Try((UUID.fromString(orgSegment), UUID.fromString(projSegment)))
          .map {
            case (orgUuid, projUuid) =>
              onSuccess(cache.get(orgUuid, projUuid).runToFuture).flatMap {
                case Some(resource) => provide((resource.value.organizationLabel, resource.value.label))
                case None           => provide((orgSegment, projSegment))
              }
          }
          .getOrElse(provide((orgSegment, projSegment)))
    }

}
