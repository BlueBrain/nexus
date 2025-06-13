package ai.senscience.nexus.tests

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.ETag
import org.scalactic.source.Position
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers

object CacheAssertions extends Matchers {

  def expectConditionalCacheHeaders(response: HttpResponse)(implicit position: Position): Assertion =
    response.header[ETag] shouldBe defined

  def expectNoConditionalCacheHeaders(response: HttpResponse)(implicit position: Position): Assertion =
    response.header[ETag] shouldBe empty

}
