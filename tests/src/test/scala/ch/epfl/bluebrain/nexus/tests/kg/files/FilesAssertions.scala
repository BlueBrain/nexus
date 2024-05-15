package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.Materializer
import akka.util.ByteString
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.ExecutionContext

object FilesAssertions extends Matchers with OptionValues with ScalaFutures {

  def expectFileContent(
      expectedFilename: String,
      expectedContentType: ContentType,
      expectedContent: String,
      compressed: Boolean = false
  )(implicit mat: Materializer, ec: ExecutionContext): (ByteString, HttpResponse) => Assertion =
    (content: ByteString, response: HttpResponse) => {
      response.status shouldEqual StatusCodes.OK
      dispositionType(response) shouldEqual ContentDispositionTypes.attachment
      attachmentName(response) shouldEqual attachmentString(expectedFilename)
      contentType(response) shouldEqual expectedContentType
      if (compressed) {
        httpEncodings(response) shouldEqual Seq(HttpEncodings.gzip)
        decodeGzip(content) shouldEqual expectedContent
      } else
        content.utf8String shouldEqual expectedContent
    }

  private def attachmentString(filename: String): String = {
    val encodedFilename = new String(Base64.getEncoder.encode(filename.getBytes(StandardCharsets.UTF_8)))
    s"=?UTF-8?B?$encodedFilename?="
  }

  private def dispositionType(response: HttpResponse): ContentDispositionType =
    response.header[`Content-Disposition`].value.dispositionType

  private def attachmentName(response: HttpResponse): String =
    response
      .header[`Content-Disposition`]
      .value
      .params
      .get("filename")
      .value

  private def contentType(response: HttpResponse): ContentType =
    response.header[`Content-Type`].value.contentType

  private def httpEncodings(response: HttpResponse): Seq[HttpEncoding] =
    response.header[`Content-Encoding`].value.encodings

  private def decodeGzip(input: ByteString)(implicit mat: Materializer, ec: ExecutionContext): String =
    Coders.Gzip.decode(input).map(_.utf8String).futureValue
}
