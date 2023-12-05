package ch.epfl.bluebrain.nexus.delta.plugins.storage.files.routes

import cats.data.NonEmptyList
import ch.epfl.bluebrain.nexus.delta.plugins.storage.files.model.FileId
import ch.epfl.bluebrain.nexus.delta.sdk.model.IdSegment
import ch.epfl.bluebrain.nexus.delta.sourcing.model.ProjectRef
import ch.epfl.bluebrain.nexus.delta.sourcing.model.Tag.UserTag
import io.circe.{Decoder, DecodingFailure, Json}

final case class CopyFileSource(
    project: ProjectRef,
    files: NonEmptyList[FileId]
)

object CopyFileSource {

  implicit val dec: Decoder[CopyFileSource] = Decoder.instance { cur =>
    def parseSingle(j: Json, proj: ProjectRef): Decoder.Result[FileId] =
      for {
        sourceFile <- j.hcursor.get[String]("sourceFileId").map(IdSegment(_))
        sourceTag  <- j.hcursor.get[Option[UserTag]]("sourceTag")
        sourceRev  <- j.hcursor.get[Option[Int]]("sourceRev")
        fileId     <- (sourceTag, sourceRev) match {
                        case (Some(tag), None)  => Right(FileId(sourceFile, tag, proj))
                        case (None, Some(rev))  => Right(FileId(sourceFile, rev, proj))
                        case (None, None)       => Right(FileId(sourceFile, proj))
                        case (Some(_), Some(_)) =>
                          // TODO any decoding failures will return a 415 which isn't accurate most of the time. It should
                          // probably be a bad request instead.
                          Left(
                            DecodingFailure("Tag and revision cannot be simultaneously present for source file lookup", Nil)
                          )
                      }
      } yield fileId

    for {
      sourceProj <- cur.get[ProjectRef]("sourceProjectRef")
      files      <- cur.get[NonEmptyList[Json]]("files").flatMap(_.traverse(parseSingle(_, sourceProj)))
    } yield CopyFileSource(sourceProj, files)
  }
}
