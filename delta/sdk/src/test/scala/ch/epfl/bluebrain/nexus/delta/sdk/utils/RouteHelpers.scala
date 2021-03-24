package ch.epfl.bluebrain.nexus.delta.sdk.utils

import akka.http.scaladsl.model.HttpEntity.ChunkStreamPart
import akka.http.scaladsl.model.MediaTypes.`application/json`
import akka.http.scaladsl.model.{HttpEntity, HttpResponse, RequestEntity}
import akka.http.scaladsl.testkit.{RouteTestTimeout, ScalatestRouteTest}
import akka.stream.scaladsl.Source
import akka.testkit.TestDuration
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.parser.parse
import io.circe.{Json, Printer}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.charset.StandardCharsets
import scala.concurrent.duration._

trait RouteHelpers extends AnyWordSpecLike with ScalatestRouteTest with ScalaFutures with EitherValuable {

  implicit val routeTimeout: RouteTestTimeout = RouteTestTimeout(6.seconds.dilated)

  implicit def httpResponseSyntax(http: HttpResponse): HttpResponseOps                 = new HttpResponseOps(http)
  implicit def httpResponseSyntax(chunks: Source[ChunkStreamPart, Any]): HttpChunksOps = new HttpChunksOps(chunks)
  implicit def httpJsonSyntax(json: Json): JsonToHttpEntityOps                         = new JsonToHttpEntityOps(json)

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(6.seconds.dilated, 10.milliseconds)

  private def consume(source: Source[ByteString, Any]): String =
    source.runFold("")(_ ++ _.utf8String).futureValue()

  private def consume(source: Source[ByteString, Any], entries: Long): String =
    source.take(entries).runFold("")(_ ++ _.utf8String).futureValue()

  // No need to persist any cache for tests related to routes
  override def testConfigSource: String = "akka.cluster.distributed-data.durable.keys=[]"

  def asString(source: Source[ByteString, Any], entries: Option[Long] = None): String =
    entries.fold(consume(source))(consume(source, _))

  def asJson(source: Source[ByteString, Any], entries: Option[Long] = None): Json = {
    val consumed = asString(source, entries)
    parse(consumed) match {
      case Left(err)    => fail(s"Error converting '$consumed' to Json. Details: '${err.getMessage()}'")
      case Right(value) => value
    }
  }

}

object RouteHelpers extends RouteHelpers

final class JsonToHttpEntityOps(private val json: Json) extends AnyVal {
  def toEntity(implicit printer: Printer = Printer.noSpaces.copy(dropNullValues = true)): RequestEntity =
    HttpEntity(`application/json`, ByteString(printer.printToByteBuffer(json, StandardCharsets.UTF_8)))
}

final class HttpResponseOps(private val http: HttpResponse) extends AnyVal {
  def asString: String =
    RouteHelpers.asString(http.entity.dataBytes)

  def asJson: Json =
    RouteHelpers.asJson(http.entity.dataBytes)
}

final class HttpChunksOps(private val chunks: Source[ChunkStreamPart, Any]) extends AnyVal {
  def asString(entries: Long): String =
    RouteHelpers.asString(chunks.map(chunk => chunk.data()), Some(entries))

  def asJson(entries: Long): Json =
    RouteHelpers.asJson(chunks.map(chunk => chunk.data()), Some(entries))
}
