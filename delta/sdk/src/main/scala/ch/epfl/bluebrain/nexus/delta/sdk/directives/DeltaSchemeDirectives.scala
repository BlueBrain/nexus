package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.scaladsl.model.Uri.Path./
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive0, Directive1}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.directives.UriDirectives._
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.IriVocab
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.projects.{FetchContext, UUIDCache}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef}
import monix.bio.{IO, UIO}
import monix.execution.Scheduler

import java.util.UUID
import scala.util.Try

/**
  * Directives requiring interactions with the projects and organizations modules
  * @param fetchContext
  *   fetch the context for a project
  * @param fetchOrgByUuid
  *   fetch an org by its uuid
  * @param fetchProjByUuid
  *   fetch a project by its uuid
  */
final class DeltaSchemeDirectives(
    fetchContext: ProjectRef => IO[_, ProjectContext],
    fetchOrgByUuid: UUID => UIO[Option[Label]],
    fetchProjByUuid: UUID => UIO[Option[ProjectRef]]
)(implicit s: Scheduler)
    extends QueryParamsUnmarshalling {

  /**
    * Extracts the organization segment and converts it to UUID. If the conversion is possible, it attempts to fetch the
    * organization from the cache in order to retrieve the label. Otherwise it returns the fetched segment
    */
  def resolveOrg: Directive1[Label] =
    pathPrefix(Segment).flatMap { segment =>
      Try(UUID.fromString(segment))
        .map(uuid =>
          onSuccess(fetchOrgByUuid(uuid).attempt.runToFuture).flatMap {
            case Right(Some(label)) => provide(label)
            case _                  => label(segment)
          }
        )
        .getOrElse(label(segment))
    }

  /**
    * Consumes two path Segments parsing them as UUIDs and fetch the [[ProjectRef]] looking up on the ''projects''
    * bundle. It fails fast if the project with the passed UUIDs is not found.
    */
  def resolveProjectRef: Directive1[ProjectRef] = {

    def projectRefFromString(o: String, p: String): Directive1[ProjectRef] =
      for {
        org  <- label(o)
        proj <- label(p)
      } yield ProjectRef(org, proj)

    def projectFromUuids: Directive1[ProjectRef] = (uuid & uuid).tflatMap { case (oUuid, pUuid) =>
      onSuccess(fetchProjByUuid(pUuid).attempt.runToFuture).flatMap {
        case Right(Some(project)) => provide(project)
        case _                    => projectRefFromString(oUuid.toString, pUuid.toString)
      }
    }

    projectFromUuids | projectRef
  }

  def projectContext(projectRef: ProjectRef): Directive1[ProjectContext] =
    onSuccess(fetchContext(projectRef).attempt.runToFuture).flatMap {
      case Right(pc) => provide(pc)
      case Left(_)   => reject()
    }

  /**
    * Consumes a path Segment and parse it into an [[Iri]]. It fetches the project context in order to expand the
    * segment into an Iri
    */
  def iriSegment(projectRef: ProjectRef): Directive1[Iri] =
    idSegment.flatMap { idSegment =>
      onSuccess(fetchContext(projectRef).attempt.runToFuture).flatMap {
        case Right(pc) => idSegment.toIri(pc.apiMappings, pc.base).map(provide).getOrElse(reject())
        case Left(_)   => reject()
      }
    }

  /**
    * If the un-consumed request context starts by /resources/{org}/{proj}/_/{id} and it is a GET request the
    * un-consumed path it is replaced by /{rootResourceType}/{org}/{proj}/{id}
    *
    * On the other hand if the un-consumed request context starts by /resources/{org}/{proj}/{schema}/ and {schema}
    * resolves to the passed ''schemaRef'' the un-consumed path it is replaced by /{rootResourceType}/{org}/{proj}/
    *
    * Note: Use right after extracting the prefix
    */
  def replaceUri(rootResourceType: String, schemaId: Iri): Directive0 =
    replaceUriOnUnderscore(rootResourceType) & replaceUriOn(rootResourceType, schemaId)

  private def replaceUriOnUnderscore(rootResourceType: String): Directive0 =
    ((get | delete) & pathPrefix("resources") & resolveProjectRef & pathPrefix("_") & pathPrefix(Segment))
      .tflatMap { case (projectRef, id) =>
        mapRequestContext { ctx =>
          val basePath = /(rootResourceType) / projectRef.organization.value / projectRef.project.value / id
          ctx.withUnmatchedPath(basePath ++ ctx.unmatchedPath)
        }
      }
      .or(pass)

  private def replaceUriOn(rootResourceType: String, schemaId: Iri): Directive0 =
    (pathPrefix("resources") & resolveProjectRef)
      .flatMap { projectRef =>
        iriSegment(projectRef).tfilter { case Tuple1(schema) => schema == schemaId }.flatMap { _ =>
          mapRequestContext { ctx =>
            val basePath = /(rootResourceType) / projectRef.organization.value / projectRef.project.value
            ctx.withUnmatchedPath(basePath ++ ctx.unmatchedPath)
          }
        }
      }
      .or(pass)

  /**
    * Extract the ''type'' query parameter(s) as Iri
    */
  def types(implicit projectRef: ProjectRef): Directive1[Set[Iri]] =
    onSuccess(fetchContext(projectRef).attempt.runToFuture).flatMap {
      case Right(projectContext) =>
        implicit val pc: ProjectContext = projectContext
        parameter("type".as[IriVocab].*).map(_.toSet.map((iriVocab: IriVocab) => iriVocab.value))
      case _                     => provide(Set.empty[Iri])
    }
}

object DeltaSchemeDirectives extends QueryParamsUnmarshalling {

  def empty(implicit s: Scheduler): DeltaSchemeDirectives = onlyResolveOrgUuid(_ => UIO.none)

  def onlyResolveOrgUuid(fetchOrgByUuid: UUID => UIO[Option[Label]])(implicit s: Scheduler) = new DeltaSchemeDirectives(
    (ref: ProjectRef) => IO.raiseError(ProjectNotFound(ref)),
    fetchOrgByUuid,
    _ => UIO.none
  )

  def onlyResolveProjUuid(fetchProjByUuid: UUID => UIO[Option[ProjectRef]])(implicit s: Scheduler) =
    new DeltaSchemeDirectives(
      (ref: ProjectRef) => IO.raiseError(ProjectNotFound(ref)),
      _ => UIO.none,
      fetchProjByUuid
    )

  def apply(fetchContext: FetchContext[_], uuidCache: UUIDCache)(implicit s: Scheduler): DeltaSchemeDirectives =
    apply(fetchContext, uuidCache.orgLabel, uuidCache.projectRef)

  def apply(fetchContext: FetchContext[_])(implicit s: Scheduler): DeltaSchemeDirectives =
    new DeltaSchemeDirectives((ref: ProjectRef) => fetchContext.onRead(ref), _ => UIO.none, _ => UIO.none)

  def apply(
      fetchContext: FetchContext[_],
      fetchOrgByUuid: UUID => UIO[Option[Label]],
      fetchProjByUuid: UUID => UIO[Option[ProjectRef]]
  )(implicit s: Scheduler): DeltaSchemeDirectives =
    new DeltaSchemeDirectives((ref: ProjectRef) => fetchContext.onRead(ref), fetchOrgByUuid, fetchProjByUuid)
}
