package ch.epfl.bluebrain.nexus.delta.sdk.directives

import akka.http.javadsl.server.Rejections.validationRejection
import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.server.directives.BasicDirectives.extractRequestContext
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.search.Pagination._
import ch.epfl.bluebrain.nexus.delta.kernel.search.{Pagination, TimeRange}
import ch.epfl.bluebrain.nexus.delta.rdf.IriOrBNode.Iri
import ch.epfl.bluebrain.nexus.delta.sdk.marshalling.{JsonLdFormat, QueryParamsUnmarshalling}
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment.StringSegment
import ch.epfl.bluebrain.nexus.delta.sdk.model._
import ch.epfl.bluebrain.nexus.delta.sdk.model.search.PaginationConfig
import ch.epfl.bluebrain.nexus.delta.sdk.projects.model.ProjectContext
import ch.epfl.bluebrain.nexus.delta.sdk.resources.Resources
import ch.epfl.bluebrain.nexus.delta.sdk.resources.model.ResourceRejection.InvalidResourceId
import ch.epfl.bluebrain.nexus.delta.sdk.{IndexingMode, OrderingFields}
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Identity.Subject
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import ch.epfl.bluebrain.nexus.delta.sourcing.model.{Label, ProjectRef, ResourceRef}
import io.circe.Json

import java.util.UUID
import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}

trait UriDirectives extends QueryParamsUnmarshalling {

  private val simultaneousTagAndRevRejection: MalformedQueryParamRejection =
    MalformedQueryParamRejection("tag", "tag and rev query parameters cannot be present simultaneously")

  private val reservedIdSegments = Set("events", "source", "tags")

  private def limitExceededRejection(param: String, limit: Int) =
    MalformedQueryParamRejection(param, s"limit '$limit' exceeded")

  /**
    * Extract the common searchParameters (deprecated, rev, createdBy, updatedBy) from query parameters
    */
  def searchParams(implicit
      base: BaseUri
  ): Directive[(Option[Boolean], Option[Int], Option[Subject], Option[Subject])] =
    parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Int].?) &
      parameter("createdBy".as[Subject].?) &
      parameter("updatedBy".as[Subject].?)

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

  def label(s: String): Directive1[Label] =
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
    * Consumes two consecutive Path segments parsing them into two [[Label]]
    */
  def projectRef: Directive1[ProjectRef] =
    (label & label).tmap { case (org, proj) =>
      ProjectRef(org, proj)
    }

  /**
    * Extracts the annotate param as a boolean with a default false value
    */
  def annotateSource: Directive1[Boolean] = parameter("annotate".as[Boolean].withDefault(false))

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

  def noRev: Directive0 = noParameter("rev")

  /**
    * Check for the prune query parameter to be set to true
    */
  def prune: Directive0 = parameter("prune".as[Boolean]).flatMap {
    case true  => pass
    case false => reject()
  }

  /**
    * Consumes a path Segment and parse it into an [[IdSegment]]
    */
  def idSegment: Directive1[IdSegment] =
    pathPrefix(Segment).flatMap {
      case segment if reservedIdSegments.contains(segment) => reject()
      case segment                                         => provide(IdSegment(segment))
    }

  def iriSegment: Directive1[Iri] =
    pathPrefix(Segment).flatMap { segment =>
      Iri(segment) match {
        case Left(_)    => reject()
        case Right(iri) => provide(iri)
      }
    }

  def resourceRef(idSegment: IdSegment)(implicit pc: ProjectContext): Directive1[ResourceRef] =
    Resources.expandResourceRef(idSegment, pc.apiMappings, pc.base, InvalidResourceId) match {
      case Right(resourceRef) => provide(resourceRef)
      case Left(err)          => reject(validationRejection(err.getMessage))
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

  val revParam: Directive[Tuple1[Int]] = parameter("rev".as[Int])

  /**
    * Creates optional [[UserTag]] from `tag` query param.
    */
  val tagParam: Directive1[Option[UserTag]] = parameter("tag".as[UserTag].?)

  def timeRange(paramName: String): Directive1[TimeRange] = parameter(paramName.as[String].?).flatMap {
    case None        => provide(TimeRange.default)
    case Some(value) =>
      TimeRange.parse(value) match {
        case Right(range) => provide(range)
        case Left(error)  => reject(validationRejection(error.message))
      }
  }

  val createdAt: Directive1[TimeRange] = timeRange("createdAt")
  val updatedAt: Directive1[TimeRange] = timeRange("updatedAt")

  /**
    * Consumes the rev/tag query parameter and generates an [[IdSegmentRef]]
    */
  def idSegmentRef(id: IdSegment): Directive1[IdSegmentRef] =
    (parameter("rev".as[Int].?) & parameter("tag".as[UserTag].?)).tflatMap {
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
