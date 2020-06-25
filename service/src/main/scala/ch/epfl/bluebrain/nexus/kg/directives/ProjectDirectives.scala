package ch.epfl.bluebrain.nexus.kg.directives

import java.util.UUID

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1}
import cats.implicits._
import ch.epfl.bluebrain.nexus.admin.client.AdminClient
import ch.epfl.bluebrain.nexus.admin.client.types.{Organization, Project}
import ch.epfl.bluebrain.nexus.iam.client.types.AuthToken
import ch.epfl.bluebrain.nexus.iam.types.Identity.Subject
import ch.epfl.bluebrain.nexus.kg.KgError.{OrganizationNotFound, ProjectIsDeprecated, ProjectNotFound}
import ch.epfl.bluebrain.nexus.kg.cache.ProjectCache
import ch.epfl.bluebrain.nexus.kg.config.Schemas
import ch.epfl.bluebrain.nexus.kg.resources.{OrganizationRef, ProjectInitializer}
import ch.epfl.bluebrain.nexus.kg.resources.ProjectIdentifier._
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Success, Try}

object ProjectDirectives {

  // TODO: Remove when migrating ADMIN client
  implicit private val fakeToken: Option[AuthToken] = None

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
  def project(implicit
      projectCache: ProjectCache[Task],
      client: AdminClient[Task],
      s: Scheduler,
      initializer: ProjectInitializer[Task],
      subject: Subject
  ): Directive1[Project] = {

    def initialize(projectOpt: Option[Project]) =
      projectOpt.map(project => initializer(project, subject) >> Task.pure(Some(project))).getOrElse(Task.pure(None))

    def projectByLabel(orgLabel: String, projectLabel: String): Directive1[Project] = {
      val label  = ProjectLabel(orgLabel, projectLabel)
      val result = projectCache
        .get(label)
        .flatMap {
          case value @ Some(_) => Task.pure(value)
          case None            => client.fetchProject(orgLabel, projectLabel).flatMap(initialize)
        }
        .onErrorRecoverWith {
          case _ => client.fetchProject(orgLabel, projectLabel).flatMap(initialize)
        }
      onSuccess(result.runToFuture)
        .flatMap {
          case None          => failWith(ProjectNotFound(label))
          case Some(project) => provide(addDefaultMappings(project))
        }
    }

    def projectByUuid(orgUuid: UUID, projUuid: UUID): Directive1[Project] = {
      val result = projectCache
        .get(OrganizationRef(orgUuid), ProjectRef(projUuid))
        .flatMap {
          case value @ Some(_) => Task.pure(value)
          case None            => client.fetchProject(orgUuid, projUuid).flatMap(initialize)
        }
        .onErrorRecoverWith {
          case _ => client.fetchProject(orgUuid, projUuid).flatMap(initialize)
        }
      onSuccess(result.runToFuture)
        .flatMap {
          case None          => reject
          case Some(project) => provide(addDefaultMappings(project))
        }
    }

    pathPrefix(Segment / Segment).tflatMap {
      case (orgLabel, projectLabel) =>
        Try((UUID.fromString(orgLabel), UUID.fromString(projectLabel))) match {
          case Success((orgUuid, projUuid)) => projectByUuid(orgUuid, projUuid) | projectByLabel(orgLabel, projectLabel)
          case _                            => projectByLabel(orgLabel, projectLabel)
        }
    }
  }

  /**
    * Fetches organization configuration from nexus admin.
    *
    * @param label the organization label
    */
  def org(
      label: String
  )(implicit client: AdminClient[Task], s: Scheduler): Directive1[Organization] = {
    def orgByLabel: Directive1[Organization]            =
      onSuccess(client.fetchOrganization(label).runToFuture)
        .flatMap {
          case None      => failWith(OrganizationNotFound(label))
          case Some(org) => provide(org)
        }
    def orgByUuid(uuid: UUID): Directive1[Organization] =
      onSuccess(client.fetchOrganization(uuid).runToFuture)
        .flatMap {
          case None      => reject()
          case Some(org) => provide(org)
        }

    Try(UUID.fromString(label)) match {
      case Success(uuid) => orgByUuid(uuid) | orgByLabel
      case _             => orgByLabel
    }
  }

  private def addDefaultMappings(project: Project) =
    project.copy(apiMappings = project.apiMappings ++ defaultPrefixMapping)

  /**
    * @return pass when the project is not deprecated, rejects when project is deprecated
    */
  def projectNotDeprecated(implicit proj: Project): Directive0 =
    if (proj.deprecated) failWith(ProjectIsDeprecated(proj.projectLabel))
    else pass
}
