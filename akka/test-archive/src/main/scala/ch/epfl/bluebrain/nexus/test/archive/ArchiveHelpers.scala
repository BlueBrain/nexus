package ch.epfl.bluebrain.nexus.test.archive

import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import ArchiveHelpers.ArchiveContent
import io.circe.Json
import io.circe.parser.parse
import org.scalactic.source.Position
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{Assertions, OptionValues}

import java.nio.file.Files as JFiles
import java.security.MessageDigest
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*

trait ArchiveHelpers extends ScalaFutures with OptionValues { self: Assertions =>

  implicit class ByteStringMapOps(value: ArchiveContent)(implicit position: Position) {
    def entryAsJson(path: String): Json = parse(entryAsString(path)) match {
      case Left(value)  => fail(value)
      case Right(value) => value
    }

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
    val path = JFiles.createTempFile("test", ".zip")

    val futureContent = source.completionTimeout(10.seconds).runWith(FileIO.toPath(path)).flatMap { _ =>
      Archive
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
    }

    futureContent.futureValue
  }

}
object ArchiveHelpers {

  type ArchiveContent = Map[String, ByteString]

}
