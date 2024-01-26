package ch.epfl.bluebrain.nexus.tests.kg.files

import akka.http.scaladsl.coding.Coders
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.Materializer
import akka.util.ByteString
import cats.effect.IO
import ch.epfl.bluebrain.nexus.tests.Identity.storages.Coyote
import ch.epfl.bluebrain.nexus.tests.kg.files.model.FileInput
import ch.epfl.bluebrain.nexus.tests.{CirceUnmarshalling, HttpClient}
import io.circe.Json
import org.apache.commons.codec.Charsets
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers

import java.util.Base64
import scala.concurrent.ExecutionContext

class FilesDsl(deltaClient: HttpClient)(implicit mat: Materializer, ec: ExecutionContext)
    extends CirceUnmarshalling
    with Matchers
    with OptionValues
    with ScalaFutures {

  def uploadFile(
      fileInput: FileInput,
      projRef: String,
      storage: String,
      rev: Option[Int]
  ): ((Json, HttpResponse) => Assertion) => IO[Assertion] = {
    val revString = rev.map(r => s"&rev=$r").getOrElse("")
    deltaClient.uploadFile[Json](
      s"/files/$projRef/${fileInput.fileId}?storage=nxv:$storage$revString",
      fileInput.contents,
      fileInput.ct,
      fileInput.filename,
      Coyote
    )
  }

  def uploadFileWithKeywords(
      fileInput: FileInput,
      projRef: String,
      storage: String,
      rev: Option[Int],
      keywords: Map[String, String]
  ): IO[(Json, HttpResponse)] = {
    val revString = rev.map(r => s"&rev=$r").getOrElse("")
    deltaClient.uploadFileWithKeywords(
      s"/files/$projRef/${fileInput.fileId}?storage=nxv:$storage$revString",
      fileInput.contents,
      fileInput.ct,
      fileInput.filename,
      Coyote,
      keywords
    )
  }

  def expectFileContentAndMetadata(
      expectedFilename: String,
      expectedContentType: ContentType,
      expectedContent: String,
      compressed: Boolean = false
  ): (ByteString, HttpResponse) => Assertion =
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
    val encodedFilename = new String(Base64.getEncoder.encode(filename.getBytes(Charsets.UTF_8)))
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

  private def decodeGzip(input: ByteString): String =
    Coders.Gzip.decode(input).map(_.utf8String).futureValue
}
