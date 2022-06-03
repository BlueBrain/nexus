package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model.Uri.Path./
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.{FetchProject, FetchProjectByUuid}
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.discardEntityAndForceEmit
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.IriVocab
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{JsonLdFormat, QueryParamsUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{Project, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.{Pagination, PaginationConfig}
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingMode, OrderingFields, Organizations, Projects}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Label
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import io.circe.Json
import monix.execution.Scheduler

import java.util.UUID
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

trait UriDirectives extends QueryParamsUnmarshalling {

  val simultaneousTagAndRevRejection: MalformedQueryParamRejection =
    MalformedQueryParamRejection("tag", "tag and rev query parameters cannot be present simultaneously")

  private val reservedIdSegments = Set("events", "source", "tags")

  private def limitExceededRejection(param: String, limit: Int) =
    MalformedQueryParamRejection(param, s"limit '$limit' exceeded")

  /**
    * Extract the common searchParameters (deprecated, rev, createdBy, updatedBy) from query parameters
    */
  def searchParams(implicit
      base: BaseUri
  ): Directive[(Option[Boolean], Option[Long], Option[Subject], Option[Subject])] =
    parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("createdBy".as[Subject].?) &
      parameter("updatedBy".as[Subject].?)

  /**
    * Extract the ''type'' query parameter(s) as Iri
    */
  def types(fetchProject: FetchProject)(implicit projectRef: ProjectRef, sc: Scheduler): Directive1[Set[Iri]] =
    onSuccess(fetchProject(projectRef).attempt.runToFuture).flatMap {
      case Right(project) =>
        implicit val p: Project = project
        parameter("type".as[IriVocab].*).map(_.toSet.map((iriVocab: IriVocab) => iriVocab.value))
      case _              =>
        provide(Set.empty[Iri])
    }

  /**
    * Extract the ''sort'' query parameter(s) and provide an Ordering
    */
  def sort[A: OrderingFields]: Directive1[Ordering[ResourceF[A]]] = {

    def ordering(field: String) = {
      val (fieldName, descending) =
        if (field.startsWith("-") || field.startsWith("+")) (field.drop(1), field.startsWith("-"))
        else (field, false)
      ResourceF.sortBy[A](fieldName).map(ord => if (descending) ord.reverse else ord).toRight(fieldName)
    }
    parameter("sort".as[String].*).map(_.toList.reverse).flatMap {
      case Nil           => provide(ResourceF.defaultSort)
      case field :: tail =>
        ordering(field).flatMap(tail.foldM(_)((acc, field) => ordering(field).map(acc.orElse))) match {
          case Left(f)         => reject(MalformedQueryParamRejection("sort", s"'$f' cannot be used as a sorting value."))
          case Right(ordering) => provide(ordering)
        }
    }
  }

  /**
    * When ''prefix'' exists, consumes the leading slash and the following ''prefix'' value.
    */
  def baseUriPrefix(prefix: Option[Label]): Directive[Unit] =
    prefix match {
      case Some(Label(prefixSegment)) => pathPrefix(prefixSegment)
      case None                       => tprovide(())
    }

  private def label(s: String): Directive1[Label] =
    Label(s) match {
      case Left(err)                               => reject(validationRejection(err.getMessage))
      case Right(label) if label.value == "events" => reject()
      case Right(label)                            => provide(label)
    }

  /**
    * Consumes a Path segment parsing them into a [[Label]]
    */
  def label: Directive1[Label] = pathPrefix(Segment).flatMap(label)

  /**
    * Consumes a path Segment parsing it in a UUID
    */
  def uuid: Directive1[UUID] =
    pathPrefix(Segment).flatMap { str =>
      Try(UUID.fromString(str)) match {
        case Failure(_)    => reject()
        case Success(uuid) => provide(uuid)
      }
    }

  /**
    * Extracts the organization segment and converts it to UUID. If the conversion is possible, it attempts to fetch the
    * organization from the cache in order to retrieve the label. Otherwise it returns the fetched segment
    */
  def orgLabel(organizations: Organizations)(implicit s: Scheduler): Directive1[Label] =
    pathPrefix(Segment).flatMap { segment =>
      Try(UUID.fromString(segment))
        .map(uuid =>
          onSuccess(organizations.fetch(uuid).attempt.runToFuture).flatMap {
            case Right(resource) => provide(Label.unsafe(resource.value.label.value))
            case Left(_)         => label(segment)
          }
        )
        .getOrElse(label(segment))
    }

  /**
    * Consumes two consecutive Path segments parsing them into two [[Label]]
    */
  def projectRef: Directive1[ProjectRef] =
    (label & label).tmap { case (org, proj) =>
      ProjectRef(org, proj)
    }

  /**
    * Consumes two path Segments parsing them as UUIDs and fetch the [[ProjectRef]] looking up on the ''projects''
    * bundle. It fails fast if the project with the passed UUIDs is not found.
    */
  def projectRef(
      fetchByUuid: FetchProjectByUuid
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Directive1[ProjectRef] = {

    def projectRefFromString(o: String, p: String): Directive1[ProjectRef] =
      for {
        org  <- label(o)
        proj <- label(p)
      } yield ProjectRef(org, proj)

    def projectFromUuids: Directive1[ProjectRef] = (uuid & uuid).tflatMap { case (oUuid, pUuid) =>
      onSuccess(fetchByUuid(pUuid).attempt.runToFuture).flatMap {
        case Right(project) if project.organizationUuid == oUuid => provide(project.ref)
        case Right(_)                                            => Directive(_ => discardEntityAndForceEmit(ProjectNotFound(oUuid, pUuid): ProjectRejection))
        case Left(_)                                             => projectRefFromString(oUuid.toString, pUuid.toString)
      }
    }

    projectFromUuids | projectRef
  }

  /**
    * This directive passes when the query parameter specified is not present
    *
    * @param name
    *   the parameter name
    */
  def noParameter(name: String): Directive0 =
    extractRequestContext flatMap { ctx =>
      Try(ctx.request.uri.query()) match {
        case Success(query) if query.toMap.contains(name) =>
          reject(MalformedQueryParamRejection(name, "the provided query parameter should not be present"))
        case _                                            => pass
      }
    }

  /**
    * Consumes a path Segment and parse it into an [[IdSegment]]
    */
  def idSegment: Directive1[IdSegment] =
    pathPrefix(Segment).flatMap {
      case segment if reservedIdSegments.contains(segment) => reject()
      case segment                                         => provide(IdSegment(segment))
    }

  /**
    * Consumes a path Segment and parse it into a [[UserTag]]
    */
  def tagLabel: Directive1[UserTag] =
    pathPrefix(Segment).flatMap { segment =>
      UserTag(segment) match {
        case Right(tagLabel) => provide(tagLabel)
        case Left(err)       => reject(validationRejection(err.message))
      }
    }

  /**
    * Consumes a path Segment and parse it into an [[Iri]]. It fetches the project in order to expand the segment into
    * an Iri
    */
  def iriSegment(projectRef: ProjectRef, fetchProject: FetchProject)(implicit sc: Scheduler): Directive1[Iri] =
    idSegment.flatMap { idSegment =>
      onSuccess(fetchProject(projectRef).attempt.runToFuture).flatMap {
        case Right(project) => idSegment.toIri(project.apiMappings, project.base).map(provide).getOrElse(reject())
        case Left(_)        => reject()
      }
    }

  /**
    * Consumes the segment into [[IdSegmentRef]] and the rev/tag query parameter and generates an [[IdSegmentRef]]
    */
  val idSegmentRef: Directive1[IdSegmentRef] =
    idSegment.flatMap(idSegmentRef(_))

  /**
    * Creates [[IndexingMode]] from `indexing` query param. Defaults to [[IndexingMode.Async]].
    */
  val indexingMode: Directive1[IndexingMode] = parameter("indexing".as[String].?).flatMap {
    case None | Some("async") => provide(IndexingMode.Async)
    case Some("sync")         => provide(IndexingMode.Sync)
    case Some(_)              =>
      reject(
        MalformedQueryParamRejection(
          "indexing",
          "Invalid value of indexing type, allowed values are 'async' or 'sync'."
        )
      )
  }

  /**
    * Consumes the rev/tag query parameter and generates an [[IdSegmentRef]]
    */
  def idSegmentRef(id: IdSegment): Directive1[IdSegmentRef] =
    (parameter("rev".as[Long].?) & parameter("tag".as[UserTag].?)).tflatMap {
      case (Some(_), Some(_)) => reject(simultaneousTagAndRevRejection)
      case (Some(rev), _)     => provide(IdSegmentRef(id, rev))
      case (_, Some(tag))     => provide(IdSegmentRef(id, tag))
      case _                  => provide(IdSegmentRef(id))
    }

  /**
    * Converts the underscore segment as an option
    */
  def underscoreToOption(segment: IdSegment): Option[IdSegment] =
    segment match {
      case StringSegment("_") => None
      case other              => Some(other)
    }

  /**
    * Extracts pagination specific query params ''from'' and ''size'' or use defaults.
    */
  def fromPaginated(implicit qs: PaginationConfig): Directive1[FromPagination] =
    (parameter(from.as[Int] ? 0) & parameter(size.as[Int] ? qs.defaultSize)).tflatMap {
      case (_, s) if s > qs.sizeLimit => reject(limitExceededRejection(size, qs.sizeLimit))
      case (f, _) if f > qs.fromLimit => reject(limitExceededRejection(from, qs.fromLimit))
      case (from, size)               => provide(FromPagination(from.max(0), size.max(1)))
    }

  /**
    * Extracts pagination specific query params ''from'' and ''after'' or use defaults.
    */
  def afterPaginated(implicit qs: PaginationConfig): Directive1[SearchAfterPagination] =
    (parameter(after.as[Json].?) & parameter(size.as[Int] ? qs.defaultSize)).tflatMap {
      case (None, _)                  => reject()
      case (_, s) if s > qs.sizeLimit => reject(limitExceededRejection(size, qs.sizeLimit))
      case (Some(after), size)        => provide(SearchAfterPagination(after, size.max(1)))
    }

  /**
    * Extracts pagination specific query params ''from'' and ''after' or ''from'' and ''size' or use defaults.
    */
  def paginated(implicit qs: PaginationConfig): Directive1[Pagination] = {
    parameters(after.as[Json].?, from.as[Int].?).tflatMap {
      case (Some(_), Some(_)) =>
        val r = MalformedQueryParamRejection(
          s"$after, $from",
          s"$after and $from query parameters cannot be present simultaneously"
        )
        reject(r)
      case _                  =>
        afterPaginated.map[Pagination](identity) or fromPaginated.map[Pagination](identity)
    }
  }

  /**
    * Extracts the ''format'' query parameter and converts it into a [[JsonLdFormat]]
    */
  def jsonLdFormatOrReject: Directive1[JsonLdFormat] =
    parameter("format".?).flatMap {
      case Some("compacted") => provide(JsonLdFormat.Compacted)
      case Some("expanded")  => provide(JsonLdFormat.Expanded)
      case Some(other)       => reject(InvalidRequiredValueForQueryParamRejection("format", "compacted|expanded", other))
      case None              => provide(JsonLdFormat.Compacted)
    }

  private def replaceUriOnUnderscore(rootResourceType: String): Directive0 =
    ((get | delete) & pathPrefix("resources") & projectRef & pathPrefix("_") & pathPrefix(Segment))
      .tflatMap { case (projectRef, id) =>
        mapRequestContext { ctx =>
          val basePath = /(rootResourceType) / projectRef.organization.value / projectRef.project.value / id
          ctx.withUnmatchedPath(basePath ++ ctx.unmatchedPath)
        }
      }
      .or(pass)

  private def replaceUriOn(
      rootResourceType: String,
      schemaId: Iri,
      fetchProject: FetchProject,
      fetchByUuid: FetchProjectByUuid
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Directive0 =
    (pathPrefix("resources") & projectRef(fetchByUuid))
      .flatMap { projectRef =>
        iriSegment(projectRef, fetchProject).tfilter { case Tuple1(schema) => schema == schemaId }.flatMap { _ =>
          mapRequestContext { ctx =>
            val basePath = /(rootResourceType) / projectRef.organization.value / projectRef.project.value
            ctx.withUnmatchedPath(basePath ++ ctx.unmatchedPath)
          }
        }
      }
      .or(pass)

  /**
    * If the un-consumed request context starts by /resources/{org}/{proj}/_/{id} and it is a GET request the
    * un-consumed path it is replaced by /{rootResourceType}/{org}/{proj}/{id}
    *
    * On the other hand if the un-consumed request context starts by /resources/{org}/{proj}/{schema}/ and {schema}
    * resolves to the passed ''schemaRef'' the un-consumed path it is replaced by /{rootResourceType}/{org}/{proj}/
    *
    * Note: Use right after extracting the prefix
    */
  def replaceUri(
      rootResourceType: String,
      schemaId: Iri,
      projects: Projects
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Directive0 =
    replaceUri(rootResourceType, schemaId, projects, projects)

  private[directives] def replaceUri(
      rootResourceType: String,
      schemaId: Iri,
      fetchProject: FetchProject,
      fetchByUuid: FetchProjectByUuid
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Directive0 =
    replaceUriOnUnderscore(rootResourceType) & replaceUriOn(rootResourceType, schemaId, fetchProject, fetchByUuid)

  /**
    * Strips the trailing spaces of the provided path, for example: for /a// the result will be /a. If the provided path
    * does not contain any trailing slashes it will be returned unmodified.
    *
    * @param path
    *   the path with optional trailing spaces
    */
  def stripTrailingSlashes(path: Path): Path = {
    @tailrec
    def strip(p: Path): Path =
      p match {
        case Path.Empty       => Path.Empty
        case Path.Slash(rest) => strip(rest)
        case other            => other
      }
    strip(path.reverse).reverse
  }

  /**
    * Creates a path matcher from the argument ''uri'' by stripping the slashes at the end of its path. The matcher is
    * applied directly to the prefix of the unmatched path.
    *
    * @param uri
    *   the uri to use as a prefix
    */
  def uriPrefix(uri: Uri): Directive0 =
    rawPathPrefix(PathMatcher(stripTrailingSlashes(uri.path), ()))
}

object UriDirectives extends UriDirectives
