package ch.epfl.bluebrain.nexus.cli.clients

import cats.effect.{Sync, Timer}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError
import ch.epfl.bluebrain.nexus.cli.CliError.ClientError.ClientStatusError
import ch.epfl.bluebrain.nexus.cli.clients.ElasticSearchClient.BulkOp
import ch.epfl.bluebrain.nexus.cli.clients.ElasticSearchClient.BulkOp.{Create, Delete, Index, Update}
import ch.epfl.bluebrain.nexus.cli.config.{AppConfig, ElasticSearchConfig, EnvConfig}
import ch.epfl.bluebrain.nexus.cli.{ClientErrOr, Console}
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import org.http4s._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`

trait ElasticSearchClient[F[_]] {

  /**
    * Creates an ElasticSearch index with the passed payload (settings and mappings)
    *
    * @param name    the index name
    * @param payload the index payload
    * @return Right(true) if the index was created, Right(false) if the index already exists. Left(err) otherwise.
    *         Results are wrapped in an effect type F[_]
    */
  def createIndex(name: String, payload: Json): F[ClientErrOr[Boolean]]

  /**
    * Deletes an ElasticSearch index
    *
    * @param name the index name
    * @return Right(true) if the index was deleted, Right(false) if the index did not exists. Left(err) otherwise.
    *         Results are wrapped in an effect type F[_]
    */
  def deleteIndex(name: String): F[ClientErrOr[Boolean]]

  /**
    * Executes the passed Bulk operations
    */
  def bulk(ops: Seq[BulkOp]): F[ClientErrOr[Unit]]

  /**
    * Creates/updates an ElasticSearch document using the passed ''json''.
    *
    * @param index the ElasticSearch index where to create the Document
    * @param id    the id of the ElasticSearch Document
    * @param json  the json used to create the ElasticSearch Document
    */
  def index(index: String, id: String, json: Json): F[ClientErrOr[Unit]]

  /**
    * Fetches an ElasticSearch Document
    *
    * @param index  the index where the document belongs to
    * @param id     the Document identifier
    * @param params optional query parameters
    */
  def get(index: String, id: String, params: Map[String, String] = Map.empty): F[ClientErrOr[Option[Json]]]

}

object ElasticSearchClient {

  private val newLine       = System.lineSeparator()
  private val sanitizeRegex = """[\s|"|*|\\|<|>|\||,|/|?]"""

  /**
    * Enumeration type for all possible bulk operations
    */
  sealed trait BulkOp extends Product with Serializable {
    def index: String
    def id: String

    private[clients] def payload: String = {
      val identifier = Json.obj("_index" -> sanitize(index).asJson, "_id" -> sanitize(id).asJson)
      this match {
        case Index(_, _, content)         =>
          Json.obj("index" -> identifier).noSpaces + newLine + content.noSpaces
        case Create(_, _, content)        =>
          Json.obj("create" -> identifier).noSpaces + newLine + content.noSpaces
        case Update(_, _, content, retry) =>
          val modified =
            if (retry > 0) identifier deepMerge Json.obj("retry_on_conflict" -> Json.fromInt(retry)) else identifier
          Json.obj("update" -> modified).noSpaces + newLine + content.noSpaces
        case _: Delete                    =>
          Json.obj("delete" -> identifier).noSpaces + newLine
      }
    }
  }

  object BulkOp {
    final case class Index(index: String, id: String, content: Json)                  extends BulkOp
    final case class Create(index: String, id: String, content: Json)                 extends BulkOp
    final case class Update(index: String, id: String, content: Json, retry: Int = 0) extends BulkOp
    final case class Delete(index: String, id: String)                                extends BulkOp
  }

  private def sanitize(index: String): String =
    index.replaceAll(sanitizeRegex, "_").dropWhile(_ == '_')

  private class LiveElasticSearchClient[F[_]: Timer: Console: Sync](
      client: Client[F],
      config: ElasticSearchConfig,
      env: EnvConfig
  ) extends AbstractHttpClient[F](client, env)
      with ElasticSearchClient[F] {

    private val `application/x-ndjson`: `Content-Type` = `Content-Type`(new MediaType("application", "x-ndjson"))
    private val resourceAlreadyExistsErr               = "resource_already_exists_exception"

    override def createIndex(name: String, payload: Json): F[ClientErrOr[Boolean]] = {
      val uri = config.endpoint / sanitize(name)
      val req = Request[F](Method.PUT, uri).withEntity(payload)
      executeDiscard(req, true).map {
        case Left(err @ ClientStatusError(Status.BadRequest, _)) =>
          err.jsonMessage.flatMap(parseError) match {
            case Some((`resourceAlreadyExistsErr`, _)) => Right(false)
            case Some((_, reason))                     => Left(ClientStatusError(Status.BadRequest, reason))
            case _                                     => Left(err)
          }
        case other                                               => other
      }
    }

    override def deleteIndex(name: String): F[ClientErrOr[Boolean]] = {
      val uri = config.endpoint / sanitize(name)
      val req = Request[F](Method.DELETE, uri)
      executeDiscard(req, returnValue = true).map {
        case Left(ClientStatusError(Status.NotFound, _)) => Right(false)
        case other                                       => other
      }
    }

    override def get(index: String, id: String, params: Map[String, String]): F[ClientErrOr[Option[Json]]] = {
      val uri = (config.endpoint / sanitize(index) / "_source" / sanitize(id)).withQueryParams(params)
      val req = Request[F](Method.GET, uri)
      executeParse[Json](req).map {
        case Right(json)                                 => Right(Some(json))
        case Left(ClientStatusError(Status.NotFound, _)) => Right(None)
        case Left(err)                                   => Left(err)
      }
    }

    override def bulk(ops: Seq[BulkOp]): F[ClientErrOr[Unit]] =
      ops match {
        case Nil => F.pure(Right(()))
        case _   =>
          val uri     = config.endpoint / "_bulk"
          val payload = ops.map(_.payload).mkString("", newLine, newLine)
          val req     = Request[F](Method.POST, uri).withEntity(payload).withContentType(`application/x-ndjson`)
          executeParse[Json](req).map {
            case Right(json) =>
              if (json.hcursor.get[Boolean]("errors").getOrElse(false))
                Left(
                  ClientError.unsafe(
                    json.hcursor
                      .get[Vector[JsonObject]]("items")
                      .getOrElse(Vector.empty[JsonObject])
                      .collectFirstSome(obj => obj.values.toList.collectFirstSome(innerJson => parseStatus(innerJson)))
                      .getOrElse(Status.BadRequest),
                    "Error on executing one of the ElasticSearch bulk items"
                  )
                )
              else Right(())
            case Left(err)   => Left(err)
          }
      }

    override def index(index: String, id: String, json: Json): F[ClientErrOr[Unit]] = {
      val uri = config.endpoint / sanitize(index) / "_doc" / sanitize(id)
      val req = Request[F](Method.PUT, uri).withEntity(json)
      executeDiscard(req, returnValue = ())
    }

    private def parseStatus(json: Json): Option[Status] =
      json.hcursor.get[Int]("status").map(Status(_)).toOption.filterNot(_.isSuccess)

    private def parseError(json: Json): Option[(String, String)] = {
      val c = json.hcursor.downField("error")
      (c.get[String]("type"), c.get[String]("reason")).mapN((tpe, reason) => (tpe, reason)).toOption
    }
  }

  /**
    * Construct an instance of [[ElasticSearchClient]].
    *
    * @param client  the underlying HTTP client.
    * @param config  the application config
    * @param console [[Console]] for logging
    */
  final def apply[F[_]: Sync: Timer](
      client: Client[F],
      config: AppConfig,
      console: Console[F]
  ): ElasticSearchClient[F] = {
    implicit val c: Console[F] = console
    new LiveElasticSearchClient[F](client, config.literature.elasticSearch.elasticSearchConfig, config.env)
  }
}
