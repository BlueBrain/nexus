package ch.epfl.bluebrain.nexus.delta.utils

import akka.http.scaladsl.model.HttpResponse
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

trait RouteHelpers extends ScalaFutures with EitherValuable {

  implicit def httpResponseSyntax(http: HttpResponse): HttpResponseOps = new HttpResponseOps(http)

  implicit override def patienceConfig: PatienceConfig = PatienceConfig(10.second, 10.milliseconds)

  private def consume(source: Source[ByteString, Any])(implicit mt: Materializer): String =
    source.runFold("")(_ ++ _.utf8String).futureValue

  def asString(source: Source[ByteString, Any])(implicit mt: Materializer): String =
    consume(source)

  def asJson(source: Source[ByteString, Any])(implicit mt: Materializer): Json =
    parse(consume(source)).rightValue

}

object RouteHelpers extends RouteHelpers

final class HttpResponseOps(private val http: HttpResponse) extends AnyVal {
  def asString(implicit mt: Materializer): String =
    RouteHelpers.asString(http.entity.dataBytes)

  def asJson(implicit mt: Materializer): Json =
    RouteHelpers.asJson(http.entity.dataBytes)
}
