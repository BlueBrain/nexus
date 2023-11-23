package ch.epfl.bluebrain.nexus

import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.Path
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Source}
import akka.util.ByteString
import ch.epfl.bluebrain.nexus.storage.File.FileAttributes
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.StorageConfig
import ch.epfl.bluebrain.nexus.storage.config.Contexts.errorCtxIri
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import java.net.URLDecoder
import java.nio.file.{Path => JPath, Paths}
import scala.annotation.tailrec
import scala.util.Try
import fs2.io.file.{Path => Fs2Path}

package object storage {

  /**
    * Source where the Output is ByteString and the Materialization is Any
    */
  type AkkaSource = Source[ByteString, Any]

  /**
    * Rejection or file attributes
    */
  type RejOrAttributes = Either[Rejection, FileAttributes]

  /**
    * Rejection or Path wrapped
    */
  type RejOrPath = Either[Rejection, JPath]

  /**
    * Rejection or Out attributes
    */
  type RejOr[Out] = Either[Rejection, Out]

  implicit val encFs2Path: Encoder[Fs2Path] = Encoder[String].contramap[Fs2Path](_.toString)
  implicit val encJPath: Encoder[JPath]     = Encoder[String].contramap[JPath](_.toString)

  implicit val encUriPath: Encoder[Path] = Encoder.encodeString.contramap(_.toString())
  implicit val decUriPath: Decoder[Path] = Decoder.decodeString.emapTry(s => Try(Path(s)))

  implicit class PathSyntax(private val path: JPath) extends AnyVal {

    /**
      * Converts a Java Path to an Akka [[Uri]]
      */
    def toAkkaUri: Uri = {
      val pathString = path.toUri.toString
      if (pathString.endsWith("/")) Uri(pathString.dropRight(1)) else Uri(pathString)
    }
  }

  /**
    * Build a Json error message that contains the keys @context and @type
    */
  def jsonError(json: Json): Json = {
    val typed = json.hcursor.get[String]("@type").map(v => Json.obj("@type" -> v.asJson)).getOrElse(Json.obj())
    typed deepMerge Json.obj("@context" -> Json.fromString(errorCtxIri.toString))
  }

  def folderSource(path: JPath): AkkaSource = Directory.walk(path).via(TarFlow.writer(path))

  def fileSource(path: JPath): AkkaSource = FileIO.fromPath(path)

  /**
    * Checks if the ''target'' path is a descendant of the ''parent'' path. E.g.: path = /some/my/path ; parent = /some
    * will return true E.g.: path = /some/my/path ; parent = /other will return false
    */
  def descendantOf(target: JPath, parent: JPath): Boolean =
    inner(parent, target.getParent)

  @tailrec
  @SuppressWarnings(Array("NullParameter"))
  def inner(parent: JPath, child: JPath): Boolean = {
    if (child == null) false
    else if (parent == child) true
    else inner(parent, child.getParent)
  }

  private def decode(path: Uri.Path): String =
    Try(URLDecoder.decode(path.toString, "UTF-8")).getOrElse(path.toString())

  def basePath(config: StorageConfig, name: String, protectedDir: Boolean = true): JPath = {
    val path = config.rootVolume.resolve(name).normalize()
    if (protectedDir) path.resolve(config.protectedDirectory).normalize() else path
  }

  def filePath(config: StorageConfig, name: String, path: Uri.Path, protectedDir: Boolean = true): JPath = {
    val filePath = Paths.get(decode(path))
    if (filePath.isAbsolute) filePath.normalize()
    else basePath(config, name, protectedDir).resolve(filePath).normalize()
  }
}
