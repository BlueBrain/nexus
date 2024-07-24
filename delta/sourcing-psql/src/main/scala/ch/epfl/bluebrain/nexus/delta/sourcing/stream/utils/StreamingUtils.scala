package ch.epfl.bluebrain.nexus.delta.sourcing.stream.utils

import cats.effect.{IO, Resource}
import cats.effect.std.Hotswap
import fs2.io.file.{FileHandle, Files, Flag, Flags, Path, WriteCursor}
import fs2.{text, Pipe, Pull, Stream}

object StreamingUtils {

  private val flags = Flags.Write

  private val lineSeparator = "\n"

  private val newLine = Stream.emit(lineSeparator)

  def readLines(path: Path) =
    Files[IO].readUtf8Lines(path).filter(_.nonEmpty)

  /**
    * Writes all data to a sequence of files, each limited to a maximum number of lines
    *
    * Adapted from fs2.io.file.Files.writeRotate (which is not preserving lines)
    *
    * @param computePath
    *   to compute the path of the first file and the subsequent ones
    * @param limit
    *   maximum number of lines
    */
  def writeRotate(computePath: IO[Path], limit: Int): Pipe[IO, String, Nothing] = {
    def openNewFile: Resource[IO, FileHandle[IO]] =
      Resource
        .eval(computePath)
        .flatMap(p => Files[IO].open(p, flags.addIfAbsent(Flag.Write)))

    def newCursor(file: FileHandle[IO]): IO[WriteCursor[IO]] =
      Files[IO].writeCursorFromFileHandle(file, flags.contains(Flag.Append))

    def go(
        fileHotswap: Hotswap[IO, FileHandle[IO]],
        cursor: WriteCursor[IO],
        acc: Int,
        s: Stream[IO, String]
    ): Pull[IO, Unit, Unit] = {
      s.pull.unconsLimit(limit - acc).flatMap {
        case Some((hd, tl)) =>
          val newAcc    = acc + hd.size
          val hdAsBytes =
            Stream.chunk(hd).intersperse(lineSeparator).append(newLine).through(text.utf8.encode)
          cursor.writeAll(hdAsBytes).flatMap { nc =>
            if (newAcc >= limit)
              Pull
                .eval {
                  fileHotswap
                    .swap(openNewFile)
                    .flatMap(newCursor)
                }
                .flatMap(nc => go(fileHotswap, nc, 0, tl))
            else
              go(fileHotswap, nc, newAcc, tl)
          }
        case None           => Pull.done
      }
    }

    in =>
      Stream
        .resource(Hotswap(openNewFile))
        .flatMap { case (fileHotswap, fileHandle) =>
          Stream.eval(newCursor(fileHandle)).flatMap { cursor =>
            go(fileHotswap, cursor, 0, in).stream.drain
          }
        }
  }

}
