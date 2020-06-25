package ch.epfl.bluebrain.nexus.kg.directives

import akka.http.scaladsl.common.{NameOptionReceptacle, NameReceptacle}
import akka.http.scaladsl.model.MediaRange
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.ParameterDirectives.ParamDefAux
import akka.http.scaladsl.server.{Directive0, Directive1, MalformedQueryParamRejection}
import akka.http.scaladsl.unmarshalling.{FromStringUnmarshaller, Unmarshaller}
import ch.epfl.bluebrain.nexus.admin.client.types.Project
import ch.epfl.bluebrain.nexus.commons.search.{FromPagination, Pagination, Sort, SortList}
import ch.epfl.bluebrain.nexus.kg.KgError.{InternalError, InvalidOutputFormat, NotFound}
import ch.epfl.bluebrain.nexus.kg.cache.StorageCache
import ch.epfl.bluebrain.nexus.kg.resources.syntax._
import ch.epfl.bluebrain.nexus.kg.routes.OutputFormat._
import ch.epfl.bluebrain.nexus.kg.routes.{JsonLDOutputFormat, OutputFormat, SearchParams}
import ch.epfl.bluebrain.nexus.kg.storage.Storage
import ch.epfl.bluebrain.nexus.rdf.Iri.AbsoluteIri
import ch.epfl.bluebrain.nexus.service.config.ServiceConfig.PaginationConfig
import ch.epfl.bluebrain.nexus.service.config.Vocabulary.nxv
import com.typesafe.scalalogging.Logger
import io.circe.Json
import io.circe.parser.parse
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global

import scala.util.{Failure, Success}

object QueryDirectives {

  private val logger                                                    = Logger[this.type]
  implicit val jsonFromStringUnmarshaller: FromStringUnmarshaller[Json] =
    Unmarshaller.strict[String, Json](parse(_).fold(throw _, identity))

  val from: String  = "from"
  val size: String  = "size"
  val after: String = "after"

  /**
    * @return the extracted storage from the request query parameters or default from the storage cache.
    */
  def storage(implicit cache: StorageCache[Task], project: Project): Directive1[Storage] =
    parameter("storage".as[AbsoluteIri].?)
      .map {
        case Some(storageId) => cache.get(project.ref, storageId)
        case None            => cache.getDefault(project.ref)
      }
      .flatMap { result =>
        onComplete(result.runToFuture).flatMap {
          case Success(Some(storage)) => provide(storage)
          case Success(None)          => failWith(NotFound())
          case Failure(err)           =>
            val message = "Error when trying to fetch storage"
            logger.error(message, err)
            failWith(InternalError(message))
        }
      }

  private def fromMalformed(implicit config: PaginationConfig) =
    MalformedQueryParamRejection("from", s"from parameter cannot be greater than ${config.fromLimit}")

  /**
    * @return the extracted pagination from the request query parameters or defaults to the preconfigured values.
    */
  def paginated(implicit config: PaginationConfig): Directive1[Pagination] =
    (parameter(from.as[Int].?) & parameter(size.as[Int] ? config.defaultSize) & parameter(after.as[Json].?))
      .tflatMap {
        case (None, size, Some(sa))                     => provide(Pagination(sa, size.max(1).min(config.sizeLimit)))
        case (Some(f), _, None) if f > config.fromLimit => reject(fromMalformed)
        case (Some(f), size, None)                      => provide(Pagination(f.max(0), size.max(1).min(config.sizeLimit)))
        case (None, size, None)                         => provide(Pagination(0, size.max(1).min(config.sizeLimit)))
        case _                                          =>
          reject(MalformedQueryParamRejection("after,from", "after and from cannot be specified at the same time"))
      }

  /**
    * @return the extracted pagination from the request query parameters or defaults to the preconfigured values.
    */
  def fromPaginated(implicit config: PaginationConfig): Directive1[FromPagination] =
    (parameter(from.as[Int].?) & parameter(size.as[Int] ? config.defaultSize)).tflatMap {
      case (Some(f), _) if f > config.fromLimit => reject(fromMalformed)
      case (Some(f), size)                      => provide(FromPagination(f.max(0), size.max(1).min(config.sizeLimit)))
      case (None, size)                         => provide(FromPagination(0, size.max(1).min(config.sizeLimit)))
    }

  /**
    * @param default the default output format when the query parameter is not present
    * @return the extracted JsonLDOutputFormat from the request query parameters or the Accept HTTP header.
    *         If the output format does not exist, it fails with [[InvalidOutputFormat]] exception.
    */
  private def jsonLDFormat(default: JsonLDOutputFormat = Compacted): Directive1[OutputFormat] =
    parameter("format".as[String] ? default.name).flatMap { outputName =>
      OutputFormat(outputName) match {
        case Some(output) => provide(output)
        case _            => failWith(InvalidOutputFormat(outputName))
      }
    }

  /**
    * @param strict  flag to decide whether or not to match the exact content type on the Accept header
    * @param default the default output format when neither of the defined types has been found
    * @return the extracted OutputFormat from the Accept HTTP header and the output query parameter.
    */
  def outputFormat(strict: Boolean, default: OutputFormat): Directive1[OutputFormat] =
    headerValueByType[Accept](()).flatMap { accept =>
      val outputs =
        if (strict) exactMatch(accept)
        else regexMatch(accept)
      outputs.filter { case (pos, _) => pos >= 0 }.sortBy { case (pos, _) => pos }.headOption match {
        case Some((_, Compacted)) => jsonLDFormat()
        case Some((_, format))    => provide(format)
        case None                 => provide(default)
      }
    }

  private def exactMatch(accept: Accept) = {
    val metaPos    =
      accept.mediaRanges.indexWhere(mr => Compacted.contentType.exists(ct => (ct.mediaType: MediaRange) == mr))
    val triplesPos = accept.mediaRanges.indexWhere(_ == (Triples.contentType.mediaType: MediaRange))
    val dotPos     = accept.mediaRanges.indexWhere(_ == (DOT.contentType.mediaType: MediaRange))
    List(metaPos -> Compacted, triplesPos -> Triples, dotPos -> DOT)
  }

  private def regexMatch(accept: Accept) = {
    val metaPos    =
      accept.mediaRanges.indexWhere(mr => Compacted.contentType.exists(jsonType => mr.matches(jsonType.mediaType)))
    val triplesPos = accept.mediaRanges.indexWhere(_.matches(Triples.contentType.mediaType))
    val dotPos     = accept.mediaRanges.indexWhere(_.matches(DOT.contentType.mediaType))
    List(metaPos -> Compacted, triplesPos -> Triples, dotPos -> DOT)
  }

  /**
    * This directive passes when the query parameter specified is not present
    *
    * @param param the parameter
    * @tparam A the type of the parameter
    */
  def noParameter[A](
      param: NameReceptacle[A]
  )(implicit paramAux: ParamDefAux[NameOptionReceptacle[A], Directive1[Option[A]]]): Directive0 = {
    parameter(param.?).flatMap {
      case Some(_) =>
        reject(MalformedQueryParamRejection(param.name, "the provided query parameter should not be present"))
      case _       =>
        pass
    }
  }

  /**
    * @return the extracted search parameters from the request query parameters.
    */
  def searchParams(fixedSchema: AbsoluteIri)(implicit project: Project): Directive1[SearchParams] =
    parameter("schema".as[AbsoluteIri] ? fixedSchema).flatMap {
      case `fixedSchema` =>
        searchParams.map(_.copy(schema = Some(fixedSchema)))
      case _             =>
        reject(MalformedQueryParamRejection("schema", "The provided schema does not match the schema on the Uri"))
    }

  private val defaultSort = SortList(List(Sort(nxv.createdAt.prefix), Sort("@id")))

  /**
    * @return the extracted search parameters from the request query parameters.
    */
  def searchParams(implicit project: Project): Directive1[SearchParams] =
    (parameter("deprecated".as[Boolean].?) &
      parameter("rev".as[Long].?) &
      parameter("schema".as[AbsoluteIri].?) &
      parameter("createdBy".as[AbsoluteIri].?) &
      parameter("updatedBy".as[AbsoluteIri].?) &
      parameter("type".as[VocabAbsoluteIri].*) &
      parameter("sort".as[Sort].*) &
      parameter("id".as[AbsoluteIri].?) &
      parameter("q".as[String].?)).tflatMap {
      case (_, _, _, _, _, _, sort, _, q) if q.nonEmpty && sort.nonEmpty     =>
        reject(MalformedQueryParamRejection("sort", "Should be omitted when 'q' parameter is present"))

      case (deprecated, rev, schema, createdBy, updatedBy, tpe, sort, id, q) =>
        val qCleaned = q.filter(_.trim.nonEmpty).map(_.toLowerCase())
        val sortList = if (sort.isEmpty && q.isEmpty) defaultSort else SortList(sort.toList.reverse)
        provide(
          SearchParams(deprecated, rev, schema, createdBy, updatedBy, tpe.map(_.value).toList, sortList, id, qCleaned)
        )
    }
}
