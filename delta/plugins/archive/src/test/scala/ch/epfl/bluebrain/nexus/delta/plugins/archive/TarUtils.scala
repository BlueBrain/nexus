package ch.epfl.bluebrain.nexus.delta.plugins.archive

import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Archive
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.delta.sdk.AkkaSource
import org.scalatest.concurrent.ScalaFutures

import java.nio.file.{Files => JFiles}
import scala.concurrent.ExecutionContext

object TarUtils extends ScalaFutures {

  def mapOf(source: AkkaSource)(implicit m: Materializer, e: ExecutionContext): Map[String, String] = {
    val path   = JFiles.createTempFile("test", ".tar")
    source.runWith(FileIO.toPath(path)).futureValue
    val result = FileIO
      .fromPath(path)
      .via(Archive.tarReader())
      .mapAsync(1) { case (metadata, source) =>
        source
          .runFold(ByteString.empty) { case (bytes, elem) =>
            bytes ++ elem
          }
          .map { bytes =>
            (metadata.filePath, bytes.utf8String)
          }
      }
      .runFold(Map.empty[String, String]) { case (map, elem) =>
        map + elem
      }
      .futureValue
    result
  }

}
