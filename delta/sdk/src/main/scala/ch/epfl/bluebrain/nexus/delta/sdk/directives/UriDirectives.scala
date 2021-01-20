package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.rdf.jsonld.context.RemoteContextResolution
import ch.epfl.bluebrain.nexus.delta.rdf.utils.JsonKeyOrdering
import ch.epfl.bluebrain.nexus.delta.sdk.Projects.FetchProject
import ch.epfl.bluebrain.nexus.delta.sdk.directives.DeltaDirectives.discardEntityAndEmit
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.QueryParamsUnmarshalling.IriVocab
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{JsonLdFormat, QueryParamsUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model.identities.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.ProjectRejection.ProjectNotFound
import ch.epfl.bluebrain.nexus.delta.sdk.model.projects.{ProjectRef, ProjectRejection}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.Pagination.{from, size, FromPagination}
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.model.{BaseUri, IdSegment, Label, ResourceF}
import ch.epfl.bluebrain.nexus.delta.sdk.{Organizations, Projects}
import monix.execution.Scheduler

import java.util.UUID
import scala.util.{Failure, Success, Try}

trait UriDirectives extends QueryParamsUnmarshalling {

  val simultaneousTagAndRevRejection =
    MalformedQueryParamRejection("tag", "tag and rev query parameters cannot be present simultaneously")

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
  def types(implicit projectRef: ProjectRef, fetchProject: FetchProject, sc: Scheduler): Directive1[Set[Iri]] =
    onSuccess(fetchProject(projectRef).attempt.runToFuture).flatMap {
      case Right(projectResource) =>
        implicit val project = projectResource.value
        parameter("type".as[IriVocab].*).map(_.toSet.map((iriVocab: IriVocab) => iriVocab.value))
      case _                      =>
        provide(Set.empty[Iri])
    }

  /**
    * Extract the ''sort'' query parameter(s) and provide an Ordering
    */
  def sort[A]: Directive1[Ordering[ResourceF[A]]] = {

    def ordering(field: String) = {
      val (fieldName, descending) =
        if (field.startsWith("-") || field.startsWith("+")) (field.drop(1), field.startsWith("-"))
        else (field, false)
      ResourceF.sortBy[A](fieldName).map(ord => if (descending) ord.reverse else ord).toRight(fieldName)
    }

    parameter("sort".as[String].*).map(_.toList.reverse).flatMap {
      case Nil           => provide(ResourceF.defaultSort)
      case field :: tail =>
        tail.foldLeft(ordering(field)) {
          case (err @ Left(_), _)  => err
          case (Right(ord), field) => ordering(field).map(ord.orElse)
        } match {
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

  private def label(s: String): Directive1[Label] = Label(s) match {
    case Left(err)    => reject(validationRejection(err.getMessage))
    case Right(label) => provide(label)
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
        case Failure(_)    => reject(validationRejection(s"Path segment '$str' is not a UUIDv4"))
        case Success(uuid) => provide(uuid)
      }
    }

  /**
    * Extracts the organization segment and converts it to UUID.
    * If the conversion is possible, it attempts to fetch the organization from the cache in order to retrieve the label. Otherwise it returns the fetched segment
    */
  def orgLabel(organizations: Organizations)(implicit s: Scheduler): Directive1[Label] =
    pathPrefix(Segment).flatMap { segment =>
      Try(UUID.fromString(segment))
        .map(uuid =>
          onSuccess(organizations.fetch(uuid).attempt.runToFuture).flatMap {
            case Right(resource) => provide(resource.value.label)
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
    * Consumes two path Segments parsing them as UUIDs and fetch the [[ProjectRef]] looking up on the ''projects'' bundle.
    * It fails fast if the project with the passed UUIDs is not found.
    */
  def projectRef(
      projects: Projects
  )(implicit s: Scheduler, cr: RemoteContextResolution, jo: JsonKeyOrdering): Directive1[ProjectRef] = {
    def projectRefFromString(o: String, p: String): Directive1[ProjectRef] =
      for {
        org  <- label(o)
        proj <- label(p)
      } yield ProjectRef(org, proj)

    def projectFromUuids: Directive1[ProjectRef] = (uuid & uuid).tflatMap { case (orgUuid, projectUuid) =>
      onSuccess(projects.fetch(projectUuid).attempt.runToFuture).flatMap {
        case Right(resource) if resource.value.organizationUuid == orgUuid => provide(resource.value.ref)
        case Right(_)                                                      => Directive(_ => discardEntityAndEmit(ProjectNotFound(orgUuid, projectUuid): ProjectRejection))
        case Left(_)                                                       => projectRefFromString(orgUuid.toString, projectUuid.toString)
      }
    }

    projectFromUuids | projectRef
  }

  /**
    * This directive passes when the query parameter specified is not present
    *
    * @param name the parameter name
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
    pathPrefix(Segment).map(IdSegment.apply)

  /**
    * Converts the underscore segment as an option
    * @param segment
    */
  def underscoreToOption(segment: IdSegment): Option[IdSegment] =
    segment match {
      case StringSegment("_") => None
      case other              => Some(other)
    }

  /**
    * Extracts pagination specific query params from the request or defaults to the preconfigured values.
    *
    * @param qs the preconfigured query settings
    */
  def paginated(implicit qs: PaginationConfig): Directive1[FromPagination] =
    (parameter(from.as[Int] ? 0) & parameter(size.as[Int] ? qs.defaultSize)).tmap { case (from, size) =>
      FromPagination(from.max(0).min(qs.fromLimit), size.max(1).min(qs.sizeLimit))
    }

  /**
    * Extracts the ''format'' query parameter and converts it into a [[JsonLdFormat]]
    * @return
    */
  def jsonLdFormat: Directive1[JsonLdFormat] =
    parameter("format".?).flatMap {
      case Some("compacted") => provide(JsonLdFormat.Compacted)
      case Some("expanded")  => provide(JsonLdFormat.Expanded)
      case Some(other)       => reject(InvalidRequiredValueForQueryParamRejection("format", "compacted|expanded", other))
      case None              => provide(JsonLdFormat.Compacted)
    }

}

object UriDirectives extends UriDirectives
