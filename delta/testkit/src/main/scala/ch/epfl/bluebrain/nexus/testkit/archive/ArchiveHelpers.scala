package ch.epfl.bluebrain.nexus.testkit.archive

import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.testkit.EitherValuable
import ch.epfl.bluebrain.nexus.testkit.archive.ArchiveHelpers.ArchiveContent
import io.circe.Json
import io.circe.parser.parse
import org.scalatest.OptionValues
import org.scalatest.concurrent.ScalaFutures

import java.nio.file.{Files => JFiles}
import scala.concurrent.ExecutionContext

import java.security.MessageDigest

trait ArchiveHelpers extends ScalaFutures with EitherValuable with OptionValues {

  implicit class ByteStringMapOps(value: ArchiveContent) {
    def entryAsJson(path: String): Json = parse(entryAsString(path)).rightValue

    def entryAsString(path: String): String = value.get(path).value.utf8String

    def entryDigest(path: String): String = {
      val digest = MessageDigest.getInstance("SHA-512")
      digest.update(value.get(path).value.asByteBuffer)
      digest.digest().map("%02x".format(_)).mkString
    }
  }

  def fromZip(byteString: ByteString)(implicit m: Materializer, e: ExecutionContext): ArchiveContent =
    fromZip(Source.single(byteString))

  def fromZip(source: Source[ByteString, Any])(implicit m: Materializer, e: ExecutionContext): ArchiveContent = {
    val path   = JFiles.createTempFile("test", ".tar")
    source.runWith(FileIO.toPath(path)).futureValue
    val result = Archive
      .zipReader(path.toFile)
      .mapAsync(1) { case (metadata, source) =>
        source
          .runFold(ByteString.empty) { case (bytes, elem) =>
            bytes ++ elem
          }
          .map { bytes =>
            (metadata.name, bytes)
          }
      }
      .runFold(Map.empty[String, ByteString]) { case (map, elem) =>
        map + elem
      }
      .futureValue
    result
  }

}
object ArchiveHelpers {

  type ArchiveContent = Map[String, ByteString]

}
