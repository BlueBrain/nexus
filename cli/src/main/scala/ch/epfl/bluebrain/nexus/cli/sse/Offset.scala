package ch.epfl.bluebrain.nexus.cli.sse

import java.nio.file.{Path, StandardOpenOption}
import java.util.UUID

import cats.effect.{Blocker, ContextShift, Sync}
import cats.implicits._
import ch.epfl.bluebrain.nexus.cli.Console
import fs2.{io, text, Stream}

import scala.util.Try
import scala.util.control.NonFatal

/**
  * An offset for events.
  */
final case class Offset(value: UUID) {
  lazy val asString: String = value.toString

  /**
    * Writes the current offset value to the passed ''path''.
    * If the passed path does not exists, it creates the path and its parent directories (if needed) and writes the current offset.
    * If the passed path already exists, it overrides its contents with the current offset.
    */
  def write[F[_]: ContextShift](path: Path)(implicit blocker: Blocker, console: Console[F], F: Sync[F]): F[Unit] = {
    val pipeF = io.file
      .exists(blocker, path)
      .ifM(
        F.delay(io.file.writeAll(path, blocker, List(StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE))),
        io.file.createDirectories(blocker, path.getParent).map(_ => io.file.writeAll(path, blocker))
      )
    pipeF
      .flatMap(write => Stream(asString).through(text.utf8Encode).through(write).compile.drain)
      .recoverWith { case NonFatal(err) =>
        console.println(s"""Failed to write offset '${asString}' to file '$path'.
                             |Error message: '${Option(err.getMessage).getOrElse("no message")}'
                             |The operation will NOT be retried.""".stripMargin)
      }
  }
}

object Offset {

  /**
    * Attempts to load an [[Offset]] from the passed ''path''.
    */
  final def load[F[_]: ContextShift](
      path: Path
  )(implicit blocker: Blocker, console: Console[F], F: Sync[F]): F[Option[Offset]] = {
    io.file
      .exists(blocker, path)
      .flatMap[Option[Offset]] { exists =>
        if (exists)
          io.file
            .readAll(path, blocker, 1024)
            .through(text.utf8Decode)
            .through(text.lines)
            .compile
            .string
            .map(apply)
        else F.pure(None)
      }
      .recoverWith { case NonFatal(err) =>
        // fail when there's an error reading the offset
        console.println(s"""Failed to read offset from file '${path.toString}'.
                           |Error message: '${Option(err.getMessage).getOrElse("no message")}'
                           |The operation will NOT be retried.""".stripMargin) >> F.raiseError(err)
      }
  }

  /**
    * Attempts to create an [[Offset]] from the passed string value.
    */
  final def apply(string: String): Option[Offset] =
    Try(UUID.fromString(string)).toOption.map(Offset(_))
}
