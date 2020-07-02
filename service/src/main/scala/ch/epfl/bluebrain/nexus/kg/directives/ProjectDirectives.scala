package ch.epfl.bluebrain.nexus.kg.directives

import java.util.UUID

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.index.{OrganizationCache, ProjectCache}
import ch.epfl.bluebrain.nexus.admin.organizations.OrganizationResource
import ch.epfl.bluebrain.nexus.admin.projects.ProjectResource
import ch.epfl.bluebrain.nexus.kg.KgError.{OrganizationNotFound, ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Success, Try}

object ProjectDirectives {

  val defaultPrefixMapping: Map[String, AbsoluteIri] = Map(
    "resource"        -> Schemas.unconstrainedSchemaUri,
    "schema"          -> Schemas.shaclSchemaUri,
    "view"            -> Schemas.viewSchemaUri,
    "resolver"        -> Schemas.resolverSchemaUri,
    "file"            -> Schemas.fileSchemaUri,
    "storage"         -> Schemas.storageSchemaUri,
    "nxv"             -> nxv.base,
    "documents"       -> nxv.defaultElasticSearchIndex.value,
    "graph"           -> nxv.defaultSparqlIndex.value,
    "defaultResolver" -> nxv.defaultResolver.value,
    "defaultStorage"  -> nxv.defaultStorage.value
  )

  /**
    * Fetches project configuration from the cache if possible, from nexus admin otherwise.
    */
  def project(implicit projectCache: ProjectCache[Task], s: Scheduler): Directive1[ProjectResource] = {

    def getByLabel(orgLabel: String, projectLabel: String): Directive1[ProjectResource] = {
      val label = ProjectLabel(orgLabel, projectLabel)
      onSuccess(projectCache.getBy(label).runToFuture).flatMap {
        case None          => failWith(ProjectNotFound(label))
        case Some(project) => provide(addDefaultMappings(project))
      }
    }

    def byUuid(orgUuid: UUID, projUuid: UUID): Directive1[ProjectResource] = {
      onSuccess(projectCache.get(orgUuid, projUuid).runToFuture).flatMap {
        case None          => reject
        case Some(project) => provide(addDefaultMappings(project))
      }
    }

    pathPrefix(Segment / Segment).tflatMap {
      case (orgLabel, projectLabel) =>
        Try((UUID.fromString(orgLabel), UUID.fromString(projectLabel))) match {
          case Success((orgUuid, projUuid)) => byUuid(orgUuid, projUuid) | getByLabel(orgLabel, projectLabel)
          case _                            => getByLabel(orgLabel, projectLabel)
        }
    }
  }

  /**
    * Fetches organization configuration from nexus admin.
    *
    * @param label the organization label
    */
  def org(label: String)(implicit orgCache: OrganizationCache[Task], s: Scheduler): Directive1[OrganizationResource] = {
    def byLabel: Directive1[OrganizationResource]            =
      onSuccess(orgCache.getBy(label).runToFuture).flatMap {
        case None      => failWith(OrganizationNotFound(label))
        case Some(org) => provide(org)
      }
    def byUuid(uuid: UUID): Directive1[OrganizationResource] =
      onSuccess(orgCache.get(uuid).runToFuture).flatMap {
        case None      => reject()
        case Some(org) => provide(org)
      }

    Try(UUID.fromString(label)) match {
      case Success(uuid) => byUuid(uuid) | byLabel
      case _             => byLabel
    }
  }

  private def addDefaultMappings(project: ProjectResource) =
    project.copy(value = project.value.copy(apiMappings = project.value.apiMappings ++ defaultPrefixMapping))

  /**
    * @return pass when the project is not deprecated, rejects when project is deprecated
    */
  def projectNotDeprecated(implicit project: ProjectResource): Directive0 =
    if (project.deprecated)
      failWith(ProjectIsDeprecated(ProjectLabel(project.value.organizationLabel, project.value.label)))
    else
      pass
}
