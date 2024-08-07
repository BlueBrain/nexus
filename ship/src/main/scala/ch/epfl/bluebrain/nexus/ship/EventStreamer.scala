package ch.epfl.bluebrain.nexus.ship

import cats.effect.IO
import cats.implicits._
import ch.epfl.bluebrain.nexus.delta.kernel.Logger
import ch.epfl.bluebrain.nexus.delta.plugins.storage.storages.operations.s3.client.S3StorageClient
import ch.epfl.bluebrain.nexus.delta.sourcing.exporter.RowEvent
import ch.epfl.bluebrain.nexus.delta.sourcing.offset.Offset
import ch.epfl.bluebrain.nexus.delta.sourcing.stream.utils.StreamingUtils
import ch.epfl.bluebrain.nexus.ship.EventStreamer.logger
import fs2.io.file.{Files, Path}
import fs2.{text, Stream}
import io.circe.parser.decode

import scala.jdk.CollectionConverters.ListHasAsScala

trait EventStreamer {

  protected def streamLines(path: Path): Stream[IO, String]

  protected def fileList(path: Path): IO[List[Path]]

  protected def isDirectory(path: Path): IO[Boolean]

  private def streamFromFile(path: Path, fromOffset: Offset): Stream[IO, RowEvent] =
    streamLines(path).zipWithIndex
      .evalMap { case (line, index) =>
        IO.fromEither(decode[RowEvent](line)).onError { err =>
          logger.error(err)(s"Error parsing to event at line $index")
        }
      }
      .filter { event => event.ordering.value >= fromOffset.value }

  private def streamFromDirectory(path: Path, fromOffset: Offset): Stream[IO, RowEvent] = {
    val sortedImportFiles = fileList(path).map(DirectoryReader(_, fromOffset))
    Stream.evals(sortedImportFiles).flatMap(streamFromFile(_, fromOffset))
  }

  def stream(path: Path, offset: Offset): Stream[IO, RowEvent] =
    Stream.eval(isDirectory(path)).flatMap { isDir =>
      if (isDir) streamFromDirectory(path, offset)
      else streamFromFile(path, offset)
    }

}

object EventStreamer {

  private val logger = Logger[EventStreamer]

  def s3eventStreamer(client: S3StorageClient, bucket: String): EventStreamer = new EventStreamer {

    override def streamLines(path: Path): Stream[IO, String] =
      for {
        lines <- client
                   .readFileMultipart(bucket, path.toString)
                   .through(text.utf8.decode)
                   .through(text.lines)
                   .filter(_.nonEmpty)
      } yield lines

    override def fileList(path: Path): IO[List[Path]] =
      client
        .listObjectsV2(bucket, path.toString)
        .map(_.contents().asScala.map(obj => Path(obj.key())).toList)

    override def isDirectory(path: Path): IO[Boolean] =
      client
        .listObjectsV2(bucket, path.toString)
        .map(_.keyCount() > 1)
  }

  def localStreamer: EventStreamer = new EventStreamer {

    override def streamLines(path: Path): Stream[IO, String] =
      StreamingUtils.readLines(path)

    override def fileList(path: Path): IO[List[Path]] =
      Files[IO].list(path).compile.toList

    override def isDirectory(path: Path): IO[Boolean] =
      Files[IO].isDirectory(path)

  }

}
