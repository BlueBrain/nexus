package ch.epfl.bluebrain.nexus.tests

import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.{Http, HttpExt}
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.HttpMethods._
import akka.http.scaladsl.model.Multipart.FormData
import akka.http.scaladsl.model.Multipart.FormData.BodyPart
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{`Accept-Encoding`, Accept, Authorization, HttpEncodings}
import akka.http.scaladsl.unmarshalling.FromEntityUnmarshaller
import akka.stream.Materializer
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Sink
import ch.epfl.bluebrain.nexus.tests.HttpClient.{jsonHeaders, logger, rdfApplicationSqlQuery, tokensMap}
import ch.epfl.bluebrain.nexus.tests.Identity.Anonymous
import com.typesafe.scalalogging.Logger
import io.circe.Json
import io.circe.parser._
import fs2._
import monix.bio.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration._

class HttpClient private (baseUrl: Uri, httpExt: HttpExt)(implicit materializer: Materializer) extends Matchers {

  def apply(req: HttpRequest): Task[HttpResponse] =
    Task.deferFuture(httpExt.singleRequest(req))

  def post[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] =
    requestAssert(POST, url, Some(body), identity, extraHeaders)(assertResponse)

  def put[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] =
    requestAssert(PUT, url, Some(body), identity, extraHeaders)(assertResponse)

  def putAttachmentFromPath[A](
      url: String,
      path: Path,
      contentType: ContentType,
      fileName: String,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] = {
    def onFail(e: Throwable) =
      fail(s"Something went wrong while processing the response for $url with identity $identity", e)
    request(
      PUT,
      url,
      Some(path),
      identity,
      (p: Path) => {
        val entity = HttpEntity(contentType, Files.readAllBytes(p))
        FormData(BodyPart.Strict("file", entity, Map("filename" -> fileName))).toEntity()
      },
      assertResponse,
      onFail,
      extraHeaders
    )
  }

  def putAttachment[A](
      url: String,
      attachment: String,
      contentType: ContentType,
      fileName: String,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] = {
    def onFail(e: Throwable) =
      fail(s"Something went wrong while processing the response for $url with identity $identity", e)
    request(
      PUT,
      url,
      Some(attachment),
      identity,
      (s: String) => {
        val entity = HttpEntity(contentType, s.getBytes)
        FormData(BodyPart.Strict("file", entity, Map("filename" -> fileName))).toEntity()
      },
      assertResponse,
      onFail,
      extraHeaders
    )
  }

  def patch[A](url: String, body: Json, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] =
    requestAssert(PATCH, url, Some(body), identity, extraHeaders)(assertResponse)

  def get[A](url: String, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] =
    requestAssert(GET, url, None, identity, extraHeaders)(assertResponse)

  def getJson[A](url: String, identity: Identity)(implicit um: FromEntityUnmarshaller[A]): Task[A] = {
    def onFail(e: Throwable) =
      throw new IllegalStateException(
        s"Something went wrong while processing the response for url: $url with identity $identity",
        e
      )
    requestJson(GET, url, None, identity, (a: A, _: HttpResponse) => a, onFail, jsonHeaders)
  }

  def delete[A](url: String, identity: Identity, extraHeaders: Seq[HttpHeader] = jsonHeaders)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] =
    requestAssert(DELETE, url, None, identity, extraHeaders)(assertResponse)

  def requestAssert[A](
      method: HttpMethod,
      url: String,
      body: Option[Json],
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(assertResponse: (A, HttpResponse) => Assertion)(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] = {
    def onFail(e: Throwable) =
      fail(
        s"Something went wrong while processing the response for url: ${method.value} $url with identity $identity",
        e
      )
    requestJson(
      method,
      url,
      body,
      identity,
      assertResponse,
      onFail,
      extraHeaders
    )
  }

  def sparqlQuery[A](url: String, query: String, identity: Identity, extraHeaders: Seq[HttpHeader] = Nil)(
      assertResponse: (A, HttpResponse) => Assertion
  )(implicit um: FromEntityUnmarshaller[A]): Task[Assertion] = {
    def onFail(e: Throwable): Assertion =
      fail(s"Something went wrong while processing the response for url: $url with identity $identity", e)
    request(
      POST,
      url,
      Some(query),
      identity,
      (s: String) => HttpEntity(rdfApplicationSqlQuery, s),
      assertResponse,
      onFail,
      extraHeaders
    )
  }

  def requestJson[A, R](
      method: HttpMethod,
      url: String,
      body: Option[Json],
      identity: Identity,
      f: (A, HttpResponse) => R,
      handleError: Throwable => R,
      extraHeaders: Seq[HttpHeader]
  )(implicit um: FromEntityUnmarshaller[A]): Task[R] =
    request(
      method,
      url,
      body,
      identity,
      (j: Json) => HttpEntity(ContentTypes.`application/json`, j.noSpaces),
      f,
      handleError,
      extraHeaders
    )

  def request[A, B, R](
      method: HttpMethod,
      url: String,
      body: Option[B],
      identity: Identity,
      toEntity: B => HttpEntity.Strict,
      f: (A, HttpResponse) => R,
      handleError: Throwable => R,
      extraHeaders: Seq[HttpHeader]
  )(implicit um: FromEntityUnmarshaller[A]): Task[R] =
    apply(
      HttpRequest(
        method = method,
        uri = s"$baseUrl$url",
        headers = identity match {
          case Anonymous => extraHeaders
          case _         => tokensMap.get(identity) +: extraHeaders
        },
        entity = body.fold(HttpEntity.Empty)(toEntity)
      )
    ).flatMap { res =>
      Task
        .deferFuture {
          um(res.entity)(global, materializer)
        }
        .map {
          f(_, res)
        }
        .onErrorHandleWith { e =>
          for {
            _ <- Task {
                   logger.error(s"Status ${res.status} for url $baseUrl$url", e)
                 }
          } yield {
            handleError(e)
          }
        }
    }

  def stream[A, B](
      url: String,
      nextUrl: A => Option[String],
      lens: A => B,
      identity: Identity,
      extraHeaders: Seq[HttpHeader] = jsonHeaders
  )(implicit um: FromEntityUnmarshaller[A]): Stream[Task, B] = {
    def onFail(e: Throwable) =
      throw new IllegalStateException(
        s"Something went wrong while processing the response for url: $baseUrl$url with identity $identity",
        e
      )
    Stream.unfoldLoopEval[Task, String, B](url) { currentUrl =>
      requestJson[A, A](
        GET,
        currentUrl,
        None,
        identity,
        (a: A, _: HttpResponse) => a,
        onFail,
        extraHeaders
      ).map { a =>
        (lens(a), nextUrl(a))
      }
    }
  }

  def sseEvents(
      url: String,
      identity: Identity,
      initialLastEventId: UUID,
      take: Long = 100L,
      takeWithin: FiniteDuration = 30.seconds
  )(assertResponse: Seq[(Option[String], Option[Json])] => Assertion): Task[Assertion] = {
    def send(request: HttpRequest): Future[HttpResponse] =
      apply(request.addHeader(tokensMap.get(identity))).runToFuture
    Task
      .deferFuture {
        EventSource(s"$baseUrl$url", send, initialLastEventId = Some(initialLastEventId.toString))
          //drop resolver, views and storage events
          .take(take)
          .takeWithin(takeWithin)
          .runWith(Sink.seq)
      }
      .map { seq =>
        assertResponse(
          seq.map { s =>
            (s.eventType, parse(s.data).toOption)
          }
        )
      }
  }

}

object HttpClient {

  private val logger = Logger[this.type]

  val deltaUrl: Uri = Uri(s"http://${System.getProperty("delta:8080")}/v1")

  val tokensMap: ConcurrentHashMap[Identity, Authorization] = new ConcurrentHashMap[Identity, Authorization]

  val acceptAll: Seq[Accept] = Seq(Accept(MediaRanges.`*/*`))

  val jsonHeaders: Seq[HttpHeader] = Accept(MediaTypes.`application/json`) :: Nil

  val rdfApplicationSqlQuery: MediaType.WithFixedCharset =
    MediaType.applicationWithFixedCharset("sparql-query", `UTF-8`)
  val sparqlQueryHeaders: Seq[HttpHeader]                = Accept(rdfApplicationSqlQuery) :: Nil

  val gzipHeaders: Seq[HttpHeader] = Seq(Accept(MediaRanges.`*/*`), `Accept-Encoding`(HttpEncodings.gzip))

  def apply(baseUrl: Uri)(implicit as: ActorSystem, materializer: Materializer) =
    new HttpClient(baseUrl, Http())(materializer)
}
